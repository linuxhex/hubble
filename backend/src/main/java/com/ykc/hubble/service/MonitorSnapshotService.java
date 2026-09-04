package com.ykc.hubble.service;

import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.vo.SlsKeywordVO;
import com.ykc.hubble.vo.SnapshotPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 大盘快照采集服务：按固定周期扫描启用的监控项，
 * 对「在每日时间窗内且到点」的项执行一次 SLS count，结果写入快照缓存；
 * 采集到粉盘/红盘时通过 {@link AlertPushService} 实时推送 + 钉钉群通知。
 * <p>
 * 支持高峰/低峰时段自动切换阈值，通知包含趋势图、看板链接和详细指标。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorSnapshotService {

    private final AlertConfigService alertConfigService;
    private final SlsKeywordService slsKeywordService;
    private final SlsQueryClient slsQueryClient;
    private final SnapshotCache snapshotCache;
    private final MonitorProperties monitorProperties;
    private final Executor queryExecutor;
    private final AlertPushService alertPushService;
    private final DingTalkClient dingTalkClient;
    private final AlertChartGenerator alertChartGenerator;

    private final Map<Long, Long> lastCollectAt = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, HealthEvaluator.Status> lastStatus = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("MonitorSnapshotService 初始化完成");
    }

    @Scheduled(fixedDelayString = "${monitor.scan-interval-seconds:5}000")
    public void scan() {
        log.info("MonitorSnapshotService scan 开始");
        List<AlertConfig> configs;
        try {
            configs = alertConfigService.listEnabled();
            log.info("加载到 {} 个启用的监控配置", configs.size());
        } catch (Exception e) {
            log.warn("加载监控配置失败，跳过本轮采集: {}", e.getMessage());
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        for (AlertConfig cfg : configs) {
            if (!shouldCollect(cfg, now)) {
                continue;
            }
            if (!inFlight.add(cfg.getId())) {
                continue;
            }
            Long configId = cfg.getId();
            queryExecutor.execute(() -> {
                try {
                    safeCollect(cfg);
                } finally {
                    inFlight.remove(configId);
                }
            });
        }
    }

    private boolean shouldCollect(AlertConfig cfg, long nowSec) {
        if (!inDailyWindow(cfg)) {
            return false;
        }
        Long last = lastCollectAt.get(cfg.getId());
        int interval = cfg.getCollectionInterval() == null ? 60 : cfg.getCollectionInterval();
        return last == null || (nowSec - last) >= interval;
    }

    private boolean inDailyWindow(AlertConfig cfg) {
        try {
            LocalTime now = LocalTime.now();
            LocalTime start = LocalTime.parse(cfg.getStartTime());
            LocalTime end = LocalTime.parse(cfg.getEndTime());
            return !now.isBefore(start) && !now.isAfter(end);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 判断当前是否处于高峰时段
     */
    private boolean inPeakWindow(AlertConfig cfg) {
        if (cfg.getPeakStartTime() == null || cfg.getPeakEndTime() == null) {
            return false;
        }
        try {
            LocalTime now = LocalTime.now();
            LocalTime peakStart = LocalTime.parse(cfg.getPeakStartTime());
            LocalTime peakEnd = LocalTime.parse(cfg.getPeakEndTime());
            return !now.isBefore(peakStart) && !now.isAfter(peakEnd);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前生效的告警阈值（高峰时段使用高峰阈值）
     */
    private int getEffectiveThreshold(AlertConfig cfg) {
        if (inPeakWindow(cfg) && cfg.getPeakAlertThreshold() != null && cfg.getPeakAlertThreshold() > 0) {
            return cfg.getPeakAlertThreshold();
        }
        return cfg.getAlertThreshold() == null ? 0 : cfg.getAlertThreshold();
    }

    private void safeCollect(AlertConfig cfg) {
        long now = System.currentTimeMillis() / 1000;
        try {
            String logstore = monitorProperties.getDefaultQueryLogstore();
            String keywords = buildDefaultKeywords(cfg.getKeywordTemplateId());

            try {
                SlsKeywordVO template = slsKeywordService.getSlsKeywordDetail(cfg.getKeywordTemplateId());
                if (template != null) {
                    if (template.getLogstore() != null && !template.getLogstore().isBlank()) {
                        logstore = template.getLogstore();
                    }
                    if (template.getKeywords() != null && !template.getKeywords().isBlank()) {
                        keywords = template.getKeywords();
                    }
                }
            } catch (Exception e) {
                log.debug("模板查询失败(Milvus可能未运行)，使用默认查询: {}", e.getMessage());
            }

            int interval = cfg.getCollectionInterval() == null ? 60 : cfg.getCollectionInterval();
            long from = now - interval;

            String query = keywords + " | SELECT count(*) as cnt";
            var rows = slsQueryClient.queryAnalytics(logstore, query, from, now, 1);
            long count = 0;
            if (!rows.isEmpty()) {
                try {
                    count = Long.parseLong(rows.get(0).getOrDefault("cnt", "0"));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }

            snapshotCache.push(cfg.getId(), now, count);
            lastCollectAt.put(cfg.getId(), now);

            // 计算动态阈值：基于过去 7 天同时段数据
            int[] thresholds = calculateDynamicThreshold(logstore, keywords, interval, now);
            int effectiveThreshold = thresholds[0];
            int yellowThreshold = thresholds[1];

            // 使用动态阈值评估状态
            double yellowRatio = yellowThreshold > 0 ? (double) yellowThreshold / effectiveThreshold : 0.5;
            HealthEvaluator.Status status = HealthEvaluator.evaluate(count, effectiveThreshold, yellowRatio);

            log.info("监控项[{}] {} 采集: count={}, 动态阈值=[红:{}, 粉:{}], status={}",
                    cfg.getId(), cfg.getTitle(), count, effectiveThreshold, yellowThreshold, status);

            HealthEvaluator.Status prevStatus = lastStatus.get(cfg.getId());
            lastStatus.put(cfg.getId(), status);

            if (status == HealthEvaluator.Status.RED) {
                // 红盘：SSE 广播 + 钉钉群告警
                alertPushService.pushAlert(buildAlert(cfg, count, now, status, effectiveThreshold));
                sendDingTalkAlert(cfg, count, now, status, effectiveThreshold);
            } else if (status == HealthEvaluator.Status.YELLOW
                    && (prevStatus == null || prevStatus != HealthEvaluator.Status.YELLOW)) {
                // 粉盘：仅 SSE 广播，不刷钉钉群
                alertPushService.pushAlert(buildAlert(cfg, count, now, status, effectiveThreshold));
            }
        } catch (Exception e) {
            log.error("监控项[{}]采集失败，保留旧快照: {}", cfg.getId(), e.getMessage());
        }
    }

    private Map<String, Object> buildAlert(AlertConfig cfg, long count, long nowSec,
                                           HealthEvaluator.Status status, int effectiveThreshold) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("configId", cfg.getId());
        payload.put("title", cfg.getTitle() == null ? "" : cfg.getTitle());
        payload.put("logCount", count);
        payload.put("threshold", effectiveThreshold);
        payload.put("status", status.name());
        payload.put("time", nowSec * 1000L);
        payload.put("isPeak", inPeakWindow(cfg));
        return payload;
    }

    private void sendDingTalkAlert(AlertConfig cfg, long count, long nowSec,
                                   HealthEvaluator.Status status, int effectiveThreshold) {
        try {
            boolean isRed = status == HealthEvaluator.Status.RED;
            String statusColor = isRed ? "#FF4D4F" : "#FAAD14";
            String statusText = isRed ? "红盘" : "粉盘";
            String statusIcon = isRed ? "🔴" : "🟡";

            String timeStr = java.time.Instant.ofEpochSecond(nowSec)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));

            double usagePercent = effectiveThreshold > 0 ? (count * 100.0 / effectiveThreshold) : 0;

            String dashboardUrl = monitorProperties.getDashboardUrl();
            String detailUrl = String.format("%s/#/alert-dashboard?configId=%d", dashboardUrl, cfg.getId());

            // Extract service name from keyword template ID (e.g., "tpl-user-error" -> "user-service")
            String serviceName = extractServiceName(cfg.getKeywordTemplateId());
            String problemType = extractProblemType(cfg.getTitle(), cfg.getDescription());
            String dependencies = getDependencies(serviceName);

            StringBuilder text = new StringBuilder();
            
            // Header
            text.append(String.format("## %s **%s**\n\n", statusIcon, statusText));
            
            // Service info section
            text.append("**服务**：`").append(serviceName).append("`\n\n");
            text.append("**依赖方**：").append(dependencies).append("\n\n");
            
            // Problem description
            text.append("**问题类型**：").append(problemType).append("\n\n");
            if (cfg.getDescription() != null && !cfg.getDescription().isBlank()) {
                text.append("**问题描述**：").append(cfg.getDescription()).append("\n\n");
            }
            
            // Current status - prominent
            text.append("---\n\n");
            text.append("### 当前现状\n\n");
            text.append(String.format("> 错误量：<font color=\"%s\">**%d**</font> 次\n\n", 
                    statusColor, count));
            if (isRed) {
                text.append(String.format("> 已达到**红盘阈值** %d 次，需要立即处理\n\n", effectiveThreshold));
            } else {
                text.append(String.format("> 已达到**粉盘阈值**（红盘的 %.0f%%），需要关注\n\n", usagePercent));
            }
            text.append(String.format("> 检测时间：%s\n\n", timeStr));
            
            // Action
            text.append("---\n\n");
            text.append(String.format("[🔍 查看详情 →](%s)", detailUrl));

            String cardTitle = String.format("%s %s - %s", statusIcon, serviceName, statusText);
            dingTalkClient.sendRobotActionCard(cardTitle, text.toString(),
                    "查看详情", detailUrl, true);
        } catch (Exception e) {
            log.error("监控项[{}]钉钉通知发送失败: {}", cfg.getId(), e.getMessage());
        }
    }

    private String extractServiceName(String keywordTemplateId) {
        if (keywordTemplateId == null) return "unknown-service";
        if (keywordTemplateId.contains("statistics-server") || keywordTemplateId.contains("tpl-stat"))
            return "statistics-server";
        if (keywordTemplateId.contains("statistics-tob") || keywordTemplateId.contains("tpl-tob"))
            return "statistics-tob";
        if (keywordTemplateId.contains("trade-order") || keywordTemplateId.contains("tpl-order"))
            return "trade-order";
        if (keywordTemplateId.contains("device-maint") || keywordTemplateId.contains("tpl-device"))
            return "device-maint";
        if (keywordTemplateId.contains("zdl-push") || keywordTemplateId.contains("tpl-push"))
            return "zdl-push-server";
        return "unknown-service";
    }

    private String extractProblemType(String title, String description) {
        if (title == null) return "异常";
        if (title.contains("错误")) return "错误日志异常";
        if (title.contains("性能")) return "性能问题";
        if (title.contains("可用性")) return "可用性异常";
        if (title.contains("流量")) return "流量异常";
        if (title.contains("连接")) return "连接异常";
        return "服务异常";
    }

    private String getDependencies(String serviceName) {
        if ("statistics-server".equals(serviceName)) return "MySQL、Redis";
        if ("statistics-tob".equals(serviceName)) return "MySQL、statistics-server";
        if ("trade-order".equals(serviceName)) return "MySQL、Redis、MQ";
        if ("device-maint".equals(serviceName)) return "MySQL、IoT平台";
        if ("zdl-push-server".equals(serviceName)) return "MQ、第三方推送";
        return "下游服务";
    }

    private String buildDefaultKeywords(String keywordTemplateId) {
        if (keywordTemplateId == null) return "level: ERROR";
        // Map template IDs to actual SLS service names
        if (keywordTemplateId.contains("statistics-server") || keywordTemplateId.contains("tpl-stat")) 
            return "__tag__:_container_name_: statistics-server and level: ERROR";
        if (keywordTemplateId.contains("statistics-tob") || keywordTemplateId.contains("tpl-tob")) 
            return "__tag__:_container_name_: statistics-tob and level: ERROR";
        if (keywordTemplateId.contains("trade-order") || keywordTemplateId.contains("tpl-order")) 
            return "__tag__:_container_name_: trade-order and level: ERROR";
        if (keywordTemplateId.contains("device-maint") || keywordTemplateId.contains("tpl-device")) 
            return "__tag__:_container_name_: device-maint and level: ERROR";
        if (keywordTemplateId.contains("zdl-push") || keywordTemplateId.contains("tpl-push") || keywordTemplateId.contains("tpl-notification")) 
            return "__tag__:_container_name_: zdl-push-server and level: ERROR";
        // Legacy mappings
        if (keywordTemplateId.contains("user")) return "__tag__:_container_name_: user-server and level: ERROR";
        if (keywordTemplateId.contains("payment")) return "__tag__:_container_name_: payment-service and level: ERROR";
        if (keywordTemplateId.contains("gateway")) return "__tag__:_container_name_: gateway-api";
        if (keywordTemplateId.contains("auth")) return "__tag__:_container_name_: auth-server and level: ERROR";
        if (keywordTemplateId.contains("inventory")) return "__tag__:_container_name_: inventory-service and level: ERROR";
        // Default to ERROR level logs
        return "level: ERROR";
    }

    /**
     * 计算动态阈值：查询过去 7 天同时段数据，基于均值 + 标准差动态计算。
     * 红盘 = max(avg + 3σ, avg × 5)，即偏离均值 3 个标准差 或 5 倍均值（取更严者）
     * 粉盘 = max(avg + 2σ, avg × 3)，即偏离均值 2 个标准差 或 3 倍均值（取更严者）
     * σ 随数据波动自适应：波动大的服务阈值自动放宽，波动小的自动收紧，无需写死差值。
     */
    private int[] calculateDynamicThreshold(String logstore, String keywords, int interval, long now) {
        java.util.List<Long> dailyCounts = new java.util.ArrayList<>();

        // 查询过去 7 天同时段的数据
        for (int day = 1; day <= 7; day++) {
            long historicalNow = now - (day * 86400L);
            long historicalFrom = historicalNow - interval;

            try {
                String query = keywords + " | SELECT count(*) as cnt";
                var rows = slsQueryClient.queryAnalytics(logstore, query, historicalFrom, historicalNow, 1);
                if (!rows.isEmpty()) {
                    try {
                        long count = Long.parseLong(rows.get(0).getOrDefault("cnt", "0"));
                        dailyCounts.add(count);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception e) {
                log.debug("查询历史数据失败: day={}, error={}", day, e.getMessage());
            }
        }

        // 基于均值 + 标准差计算阈值
        if (!dailyCounts.isEmpty()) {
            int n = dailyCounts.size();
            double avg = dailyCounts.stream().mapToLong(Long::longValue).sum() / (double) n;
            double variance = dailyCounts.stream()
                    .mapToDouble(c -> Math.pow(c - avg, 2))
                    .sum() / n;
            double stddev = Math.sqrt(variance);

            int redThreshold = Math.max((int) Math.ceil(avg + 3 * stddev), Math.max((int) Math.ceil(avg * 5.0), 10));
            int yellowThreshold = Math.max((int) Math.ceil(avg + 2 * stddev), Math.max((int) Math.ceil(avg * 3.0), 5));
            log.debug("动态阈值计算: n={}, avg={}, σ={}, red={}, yellow={}",
                    n, avg, stddev, redThreshold, yellowThreshold);
            return new int[]{redThreshold, yellowThreshold};
        }

        // 如果历史数据不可用，使用配置的默认阈值
        log.debug("历史数据不可用，使用默认阈值");
        return new int[]{100, 50};
    }

    /**
     * 手动触发测试告警通知（用于验证钉钉通知样式）
     */
    public void triggerTestAlert(Long configId, boolean isRed) {
        AlertConfig cfg = alertConfigService.detail(configId);
        if (cfg == null) {
            throw new IllegalArgumentException("监控项不存在: " + configId);
        }
        int threshold = cfg.getAlertThreshold() == null ? 50 : cfg.getAlertThreshold();
        long count = isRed ? (long) (threshold * 1.2) : (long) (threshold * 0.6);
        HealthEvaluator.Status status = isRed ? HealthEvaluator.Status.RED : HealthEvaluator.Status.YELLOW;
        sendDingTalkAlert(cfg, count, System.currentTimeMillis() / 1000, status, threshold);
    }
}
