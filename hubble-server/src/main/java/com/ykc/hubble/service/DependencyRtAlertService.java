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
 * 应用依赖服务调用 RT 环比巡检（对齐 ARMS「应用依赖服务-响应时间环比上升」告警口径）。
 * Hubble 原有告警均为日志条数计数型，应用内部对下游依赖"变慢不变错"的劣化无日志洪峰，
 * 计数型规则天然抓不到；本巡检直接查 ARMS appstat.incall 指标（应用依赖调用统计，官方
 * 无 appstat.dependency）补齐该维度。返回为平铺结构：rt/count/rpc/rpcType 直接在 item 顶层。
 * 触发口径：涨幅≥surge_ratio（默认100%）且满足其一——高流量（最近1分钟调用次数≥min_count，
 * 对齐 ARMS 流量条件）且 RT 净增≥min_delta_ms（低RT劣化如 2ms→5ms 也能识别，同时过滤
 * Kafka 亚毫秒噪声）；或 RT 绝对值≥floor_ms（低流量防抖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyRtAlertService {

    private final ArmsClient armsClient;
    private final AlertPushService alertPushService;
    private final DingTalkClient dingTalkClient;
    private final MonitorProperties monitorProperties;
    private final AlertThresholdService alertThresholdService;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final java.util.concurrent.Executor queryExecutor;

    /** 同一 应用×依赖服务 组合的冷却起点（内存即可，重启重置不产生业务损害） */
    private final Map<String, Long> lastAlertSent = new ConcurrentHashMap<>();

    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final java.time.format.DateTimeFormatter FMT =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 每 5 分钟巡检一次：最近一个完整 5 分钟段平均 RT vs 前一段，环比超阈值且绝对值超下限时告警 */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 90 * 1000)
    public void checkDependencyRtSurge() {
        try {
            if (alertThresholdService.getDouble("dependency_rt_alert_enabled", 1.0) <= 0) {
                return;
            }
            double surgeRatio = alertThresholdService.getDouble("dependency_rt_surge_ratio", 500.0);
            double floorMs = alertThresholdService.getDouble("dependency_rt_floor_ms", 1000.0);
            double minCount = alertThresholdService.getDouble("dependency_rt_min_count", 3000.0);
            double deltaMs = alertThresholdService.getDouble("dependency_rt_min_delta_ms", 1.0);
            long cooldownMs = (long) (alertThresholdService.getDouble("dependency_rt_alert_cooldown_minutes", 180.0)
                    * 60 * 1000);

            long nowMs = System.currentTimeMillis();
            // ARMS 官方无 appstat.dependency（InternalError）；应用依赖调用统计指标是 appstat.incall，
            // 且必须按 pid 过滤（ParameterPidMissing）→ 先取全部应用，再按应用并行查双窗口
            var appsResp = armsClient.listApps();
            log.info("依赖服务 RT 巡检：应用列表获取{}",
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

            Map<String, double[]> merged = new ConcurrentHashMap<>();
            java.util.concurrent.atomic.AtomicInteger queryOk = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger queryEmpty = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger queryFail = new java.util.concurrent.atomic.AtomicInteger();
            var futures = appsResp.getTraceApps().stream()
                    .filter(app -> app.getPid() != null)
                    .map(app -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                        String pid = String.valueOf(app.getPid());
                        try {
                            var curResp = armsClient.queryMetricsWithDimension("appstat.incall",
                                    List.of("rt", "count"), nowMs - WINDOW_MS, nowMs, pid, 60000,
                                    List.of("rpcType", "rpc"));
                            var prevResp = armsClient.queryMetricsWithDimension("appstat.incall",
                                    List.of("rt", "count"), nowMs - 2 * WINDOW_MS, nowMs - WINDOW_MS, pid, 60000,
                                    List.of("rpcType", "rpc"));
                            Map<String, double[]> cur = aggregate(curResp, pid);
                            Map<String, double[]> prev = aggregate(prevResp, pid);
                            if (queryOk.get() == 0) {
                                int curItems = curResp != null && curResp.getData() != null
                                        && curResp.getData().getItems() != null
                                        ? curResp.getData().getItems().size() : -1;
                                String first = curItems > 0
                                        ? String.valueOf(curResp.getData().getItems().get(0)) : "无";
                                log.info("依赖服务 RT 巡检首应用样本: app={}, curItems={}, 首条={}",
                                        app.getAppName(), curItems, first);
                            }
                            queryOk.incrementAndGet();
                            if (cur.isEmpty() && prev.isEmpty()) {
                                queryEmpty.incrementAndGet();
                            }
                            for (var e : cur.entrySet()) {
                                double[] p = prev.get(e.getKey());
                                if (p == null) continue;
                                // {curRt加权和, curCount和, cur最近1分钟count, prevRt加权和, prevCount和}
                                merged.put(e.getKey(), new double[]{e.getValue()[0], e.getValue()[1], e.getValue()[2], p[0], p[1]});
                            }
                        } catch (Exception ex) {
                            queryFail.incrementAndGet();
                            log.warn("应用 {}（pid={}）依赖指标查询失败: {}",
                                    app.getAppName(), pid, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                        }
                    }, queryExecutor))
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            try {
                java.util.concurrent.CompletableFuture.allOf(futures)
                        .get(90 * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("依赖服务 RT 巡检总超时 90s，仅收集已完成应用");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("依赖服务 RT 巡检并行查询异常: {}", e.getMessage());
            }

            if (merged.isEmpty()) {
                log.info("依赖服务 RT 巡检：无可对比组合（应用数={}, 查询成功={}, 双窗均空={}, 失败={}）",
                        futures.length, queryOk.get(), queryEmpty.get(), queryFail.get());
                return;
            }

            int alertCount = 0;
            for (var e : merged.entrySet()) {
                double[] v = e.getValue();
                if (v[1] == 0 || v[4] == 0) continue;
                double curRt = v[0] / v[1];
                double prevRt = v[3] / v[4];
                if (prevRt <= 0) continue;
                double surge = (curRt - prevRt) / prevRt * 100;
                if (surge < surgeRatio) continue;
                // 对齐 ARMS 口径：高流量组合（最近1分钟调用次数达门槛）涨幅达标即告警，
                // 覆盖"低RT高流量变慢"场景（如 2ms→5ms、数千次/分钟），但要求 RT 净增
                // 达到 delta 下限，过滤亚毫秒级指标（Kafka 等）的相对涨幅噪声；低流量
                // 组合保留 RT 绝对值下限，防止微秒级基线抖动误报
                boolean highTraffic = v[2] >= minCount;
                if (highTraffic) {
                    if (curRt - prevRt < deltaMs) continue;
                } else if (curRt < floorMs) {
                    continue;
                }

                Long last = lastAlertSent.get(e.getKey());
                if (last != null && nowMs - last < cooldownMs) continue;
                lastAlertSent.put(e.getKey(), nowMs);
                alertCount++;
                pushSurgeAlert(e.getKey(), prevRt, curRt, surge, v[2], pidToName);
            }
            if (alertCount > 0) {
                log.info("依赖服务 RT 巡检：{} 个组合触发环比告警", alertCount);
            }
            log.info("依赖服务 RT 巡检完成: 应用数={}, 组合数={}, 告警={}",
                    futures.length, merged.size(), alertCount);
        } catch (Exception e) {
            log.warn("依赖服务 RT 巡检异常: {}", e.getMessage());
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
            // appstat.incall 实测返回平铺结构（{date, rt, rpc, count, rule, rpcType}），无 dimensions/measures 嵌套
            Object rpc = item.get("rpc");
            if (rpc == null) continue;
            Object rpcType = item.get("rpcType");
            double rt = toDouble(item.get("rt"));
            double cnt = toDouble(item.get("count"));
            if (cnt <= 0) continue;
            // 数组含义：{rt加权和, count总和, 最近1分钟count, 最近1分钟date}，date 取最大以对齐 ARMS "最近1分钟求和"口径
            double[] agg = result.computeIfAbsent(fallbackPid + "|" + (rpcType == null ? "" : rpcType) + "|" + rpc,
                    k -> new double[4]);
            agg[0] += rt * cnt;
            agg[1] += cnt;
            long date = (long) toDouble(item.get("date"));
            if (date >= agg[3]) {
                agg[2] = date == agg[3] ? agg[2] + cnt : cnt;
                agg[3] = date;
            }
        }
        return result;
    }

    /** ARMS SDK 反序列化的 item 值均为 String（实测），需兼容 String/Number 两种形态 */
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

    private void pushSurgeAlert(String comboKey, double prevRt, double curRt, double surge, double lastMinCount,
            Map<String, String> pidToName) {
        String[] parts = comboKey.split("\\|", 3);
        String pid = parts[0];
        String rpcType = parts.length > 1 ? parts[1] : "";
        String rpc = parts.length > 2 ? parts[2] : "";
        String appName = pidToName.getOrDefault(pid, pid);
        long now = System.currentTimeMillis();
        String timeStr = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(FMT);

        log.warn("依赖服务 RT 环比告警: app={} type={} rpc={} {}ms -> {}ms (+{}%) 最近1分钟{}次",
                appName, rpcType, rpc, String.format("%.2f", prevRt), String.format("%.2f", curRt),
                String.format("%.1f", surge), (long) lastMinCount);

        StringBuilder md = new StringBuilder();
        md.append("### 🔴 依赖服务响应时间环比告警\n\n");
        md.append(String.format("> 应用：**%s**\n\n", appName));
        if (!rpcType.isBlank()) {
            md.append(String.format("> 调用类型：**%s**\n\n", rpcType));
        }
        md.append(String.format("> 依赖服务：**%s**\n\n", rpc));
        md.append(String.format("> 平均RT：前5分钟 **%.2f ms** → 最近5分钟 **%.2f ms**，环比 **+%.1f%%**\n\n",
                prevRt, curRt, surge));
        md.append(String.format("> 流量：最近1分钟 **%d** 次\n\n", (long) lastMinCount));
        md.append(String.format("> 时间：%s\n\n", timeStr));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "dependency_rt_alert");
            payload.put("appName", appName);
            payload.put("rpcType", rpcType);
            payload.put("rpc", rpc);
            payload.put("prevRt", Math.round(prevRt));
            payload.put("currentRt", Math.round(curRt));
            payload.put("surgePercent", Math.round(surge));
            payload.put("lastMinuteCount", (long) lastMinCount);
            payload.put("time", now);
            alertPushService.pushAlert(payload);
        } catch (Exception e) {
            log.warn("依赖服务告警 SSE 推送失败: {}", e.getMessage());
        }

        try {
            String dashboardUrl = monitorProperties.getDashboardUrl() + "/#/service-load";
            dingTalkClient.sendRobotActionCard("依赖服务RT告警", md.toString(), "查看服务负载", dashboardUrl, true);
        } catch (Exception e) {
            log.warn("依赖服务告警钉钉推送失败: {}", e.getMessage());
        }
    }
}
