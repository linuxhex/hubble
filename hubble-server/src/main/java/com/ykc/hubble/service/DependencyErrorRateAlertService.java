package com.ykc.hubble.service;

import com.ykc.hubble.client.ArmsClient;
import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.config.MonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用依赖服务调用错误率巡检：监控下游依赖返回 5xx 错误的比例。
 * 补齐 DependencyRtAlertService 只监控 RT 劣化、不监控调用失败的盲区。
 * 直接查 ARMS appstat.incall 指标的 errorrate 字段（百分比）。
 *
 * 触发条件：错误率 ≥ threshold 且调用数 ≥ min_count。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyErrorRateAlertService {

    private final ArmsClient armsClient;
    private final AlertPushService alertPushService;
    private final DingTalkClient dingTalkClient;
    private final MonitorProperties monitorProperties;
    private final AlertThresholdService alertThresholdService;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final java.util.concurrent.Executor queryExecutor;

    /** 同一 应用×依赖服务 组合的冷却起点 */
    private final Map<String, Long> lastAlertSent = new ConcurrentHashMap<>();

    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final java.time.format.DateTimeFormatter FMT =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 每 5 分钟巡检一次 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 120 * 1000)
    public void checkDependencyErrorRate() {
        try {
            if (alertThresholdService.getDouble("dependency_error_alert_enabled", 1.0) <= 0) {
                return;
            }
            double errorThreshold = alertThresholdService.getDouble("dependency_error_rate_threshold", 10.0);
            double minCount = alertThresholdService.getDouble("dependency_error_min_count", 100.0);
            long cooldownMs = (long) (alertThresholdService.getDouble("dependency_error_alert_cooldown_minutes", 180.0)
                    * 60 * 1000);

            long nowMs = System.currentTimeMillis();
            var appsResp = armsClient.listApps();
            log.info("依赖服务错误率巡检：应用列表获取{}",
                    appsResp != null && appsResp.getTraceApps() != null
                            ? "成功，共 " + appsResp.getTraceApps().size() + " 个应用" : "为空");
            if (appsResp == null || appsResp.getTraceApps() == null || appsResp.getTraceApps().isEmpty()) {
                return;
            }

            Map<String, String> pidToName = new HashMap<>();
            for (var app : appsResp.getTraceApps()) {
                if (app.getPid() != null) {
                    pidToName.put(String.valueOf(app.getPid()), app.getAppName());
                }
            }

            // key: pid|rpcType|rpc, value: {errorRate, count}
            Map<String, double[]> merged = new ConcurrentHashMap<>();
            java.util.concurrent.atomic.AtomicInteger queryFail = new java.util.concurrent.atomic.AtomicInteger();

            var futures = appsResp.getTraceApps().stream()
                    .filter(app -> app.getPid() != null)
                    .map(app -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                        String pid = String.valueOf(app.getPid());
                        try {
                            var resp = armsClient.queryMetricsWithDimension("appstat.incall",
                                    List.of("errorrate", "count"), nowMs - WINDOW_MS, nowMs, pid, 60000,
                                    List.of("rpcType", "rpc"));
                            Map<String, double[]> result = aggregate(resp, pid);
                            merged.putAll(result);
                        } catch (Exception ex) {
                            queryFail.incrementAndGet();
                            log.warn("应用 {}（pid={}）错误率指标查询失败: {}",
                                    app.getAppName(), pid, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                    }, queryExecutor))
                    .toArray(java.util.concurrent.CompletableFuture[]::new);

            try {
                java.util.concurrent.CompletableFuture.allOf(futures)
                        .get(90 * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("依赖服务错误率巡检总超时 90s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("依赖服务错误率巡检并行查询异常: {}", e.getMessage());
            }

            if (merged.isEmpty()) {
                log.info("依赖服务错误率巡检：无数据（应用数={}, 失败={}）", futures.length, queryFail.get());
                return;
            }

            int alertCount = 0;
            int filteredByCount = 0, filteredByRate = 0, filteredByCooldown = 0;
            for (var e : merged.entrySet()) {
                double[] v = e.getValue();
                double errorRate = v[0];
                double count = v[1];

                if (count < minCount) { filteredByCount++; continue; }
                if (errorRate < errorThreshold) { filteredByRate++; continue; }

                Long last = lastAlertSent.get(e.getKey());
                if (last != null && nowMs - last < cooldownMs) { filteredByCooldown++; continue; }
                lastAlertSent.put(e.getKey(), nowMs);
                alertCount++;
                pushErrorAlert(e.getKey(), errorRate, count, pidToName);
            }

            if (alertCount > 0) {
                log.info("依赖服务错误率巡检：{} 个组合触发告警", alertCount);
            }
            log.info("依赖服务错误率巡检完成: 应用数={}, 组合数={}, 告警={}, 过滤(调用数不足={}, 错误率未达标={}, 冷却={})",
                    futures.length, merged.size(), alertCount,
                    filteredByCount, filteredByRate, filteredByCooldown);
        } catch (Exception e) {
            log.warn("依赖服务错误率巡检异常: {}", e.getMessage());
        }
    }

    private Map<String, double[]> aggregate(com.aliyuncs.arms.model.v20190808.QueryMetricByPageResponse resp,
            String fallbackPid) {
        if (resp == null || resp.getData() == null || resp.getData().getItems() == null) {
            return Map.of();
        }
        Map<String, double[]> result = new HashMap<>();
        for (var itemObj : resp.getData().getItems()) {
            if (!(itemObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<Object, Object> item = (Map<Object, Object>) itemObj;

            Object rpc = item.get("rpc");
            if (rpc == null) continue;
            Object rpcType = item.get("rpcType");
            double errorRate = toDouble(item.get("errorrate"));
            double count = toDouble(item.get("count"));
            if (count <= 0) continue;

            String key = fallbackPid + "|" + (rpcType == null ? "" : rpcType) + "|" + rpc;
            // 取最大 errorrate 和累加 count
            double[] existing = result.get(key);
            if (existing == null) {
                result.put(key, new double[]{errorRate, count});
            } else {
                existing[0] = Math.max(existing[0], errorRate);
                existing[1] += count;
            }
        }
        return result;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private void pushErrorAlert(String comboKey, double errorRate, double count, Map<String, String> pidToName) {
        String[] parts = comboKey.split("\\|", 3);
        String pid = parts[0];
        String rpcType = parts.length > 1 ? parts[1] : "";
        String rpc = parts.length > 2 ? parts[2] : "";
        String appName = pidToName.getOrDefault(pid, pid);
        long now = System.currentTimeMillis();
        String timeStr = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(FMT);

        log.warn("依赖服务错误率告警: app={} type={} rpc={} 错误率={}% 调用数={} 最近5分钟",
                appName, rpcType, rpc, String.format("%.2f", errorRate), (long) count);

        StringBuilder md = new StringBuilder();
        md.append("### 🔴 依赖服务错误率告警\n\n");
        md.append(String.format("> 应用：**%s**\n\n", appName));
        if (!rpcType.isBlank()) {
            md.append(String.format("> 调用类型：**%s**\n\n", rpcType));
        }
        md.append(String.format("> 依赖服务：**%s**\n\n", rpc));
        md.append(String.format("> 错误率：**%.2f%%**\n\n", errorRate));
        md.append(String.format("> 调用数：%d 次（最近5分钟）\n\n", (long) count));
        md.append(String.format("> 时间：%s\n\n", timeStr));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "dependency_error_rate_alert");
            payload.put("appName", appName);
            payload.put("rpcType", rpcType);
            payload.put("rpc", rpc);
            payload.put("errorRate", Math.round(errorRate * 100.0) / 100.0);
            payload.put("count", (long) count);
            payload.put("time", now);
            alertPushService.pushAlert(payload);
        } catch (Exception e) {
            log.warn("依赖服务错误率告警 SSE 推送失败: {}", e.getMessage());
        }

        try {
            String dashboardUrl = monitorProperties.getDashboardUrl() + "/#/service-load";
            dingTalkClient.sendRobotActionCard("依赖服务错误率告警", md.toString(), "查看服务负载", dashboardUrl, true);
        } catch (Exception e) {
            log.warn("依赖服务错误率告警钉钉推送失败: {}", e.getMessage());
        }
    }
}
