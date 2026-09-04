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
 * 采集到黄盘/红盘时通过 {@link AlertPushService} 实时推送 + 钉钉群通知。
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

    @Scheduled(fixedDelayString = "${monitor.scan-interval-seconds:5}000")
    public void scan() {
        List<AlertConfig> configs;
        try {
            configs = alertConfigService.listEnabled();
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
            String keywords = "*";

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

            int effectiveThreshold = getEffectiveThreshold(cfg);
            double yellowRatio = cfg.getYellowThresholdRatio() != null ? cfg.getYellowThresholdRatio() : 0.5;
            HealthEvaluator.Status status = HealthEvaluator.evaluate(count, effectiveThreshold, yellowRatio);

            HealthEvaluator.Status prevStatus = lastStatus.get(cfg.getId());
            lastStatus.put(cfg.getId(), status);

            if (status == HealthEvaluator.Status.RED) {
                alertPushService.pushAlert(buildAlert(cfg, count, now, status, effectiveThreshold));
                sendDingTalkAlert(cfg, count, now, status, effectiveThreshold);
            } else if (status == HealthEvaluator.Status.YELLOW
                    && (prevStatus == null || prevStatus != HealthEvaluator.Status.YELLOW)) {
                alertPushService.pushAlert(buildAlert(cfg, count, now, status, effectiveThreshold));
                sendDingTalkAlert(cfg, count, now, status, effectiveThreshold);
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
            String statusLabel = isRed ? "告警（RED）" : "预警（YELLOW）";
            String statusColor = isRed ? "#FF0000" : "#FAAD14";

            String timeStr = java.time.Instant.ofEpochSecond(nowSec)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            double yellowRatio = cfg.getYellowThresholdRatio() != null ? cfg.getYellowThresholdRatio() : 0.5;
            int yellowThreshold = (int) (effectiveThreshold * yellowRatio);
            double usagePercent = effectiveThreshold > 0 ? (count * 100.0 / effectiveThreshold) : 0;

            int interval = cfg.getCollectionInterval() == null ? 60 : cfg.getCollectionInterval();
            List<SnapshotPoint> history = snapshotCache.get(cfg.getId(), nowSec - interval * 10L, nowSec);
            long prevCount = history.size() > 1 ? history.get(history.size() - 2).getLogCount() : 0;
            String trend = count > prevCount ? "↑ 上升" : count < prevCount ? "↓ 下降" : "→ 持平";
            long trendDelta = count - prevCount;

            boolean isPeak = inPeakWindow(cfg);
            String peakLabel = isPeak ? "高峰时段" : "低峰时段";

            String dashboardUrl = monitorProperties.getDashboardUrl();
            String fullDashboardUrl = dashboardUrl + "/#/dashboard";

            StringBuilder text = new StringBuilder();
            text.append(String.format("### Hubble 监控%s\n\n", isRed ? "告警" : "预警"));
            text.append(String.format("- **监控项**: %s\n", cfg.getTitle()));
            text.append(String.format("- **状态**: <font color=\"%s\">%s</font>\n", statusColor, statusLabel));
            text.append(String.format("- **当前时段**: %s\n", peakLabel));
            text.append(String.format("- **当前命中量**: %d\n", count));
            text.append(String.format("- **红盘阈值**: %d\n", effectiveThreshold));
            text.append(String.format("- **黄盘阈值**: %d\n", yellowThreshold));
            text.append(String.format("- **阈值占比**: %.1f%%\n", usagePercent));
            text.append(String.format("- **变化趋势**: %s（%+d）\n", trend, trendDelta));
            text.append(String.format("- **触发时间**: %s\n", timeStr));
            if (cfg.getDescription() != null && !cfg.getDescription().isBlank()) {
                text.append(String.format("- **说明**: %s\n", cfg.getDescription()));
            }
            text.append(String.format("\n[查看监控大盘](%s)\n", fullDashboardUrl));

            String chartImage = alertChartGenerator.generateBase64Chart(cfg, count, nowSec);
            if (chartImage != null) {
                text.append(String.format("\n![监控趋势](%s)\n", chartImage));
            }

            String cardTitle = String.format("Hubble 监控%s - %s", isRed ? "告警" : "预警", cfg.getTitle());
            dingTalkClient.sendRobotActionCard(cardTitle, text.toString(),
                    "查看监控大盘", fullDashboardUrl, true);
        } catch (Exception e) {
            log.error("监控项[{}]钉钉通知发送失败: {}", cfg.getId(), e.getMessage());
        }
    }
}
