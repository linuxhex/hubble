package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.vo.SlsKeywordVO;
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
 * 采集到红盘时通过 {@link AlertPushService} 实时推送。
 * <p>
 * 采集任务提交到 queryExecutor 线程池并行执行，单个监控项采集失败不影响其他项。
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

    /**
     * 监控项ID -> 上次采集时间（秒）：scan 线程读、采集线程写，故用并发容器
     */
    private final Map<Long, Long> lastCollectAt = new ConcurrentHashMap<>();

    /**
     * 正在采集中的监控项ID集合：防止同一项并发重复采集污染快照（scan 线程加入、采集线程清除）
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 定时扫描：周期由 monitor.scan-interval-seconds 控制（默认 5s）
     */
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
            // 同一监控项上一轮采集未完成则跳过，避免并发重复采集污染快照
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

            // 使用分析查询而不是GetHistograms，因为GetHistograms返回0
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

            if (HealthEvaluator.evaluate(count, cfg.getAlertThreshold()) == HealthEvaluator.Status.RED) {
                alertPushService.pushAlert(buildAlert(cfg, count, now));
            }
        } catch (Exception e) {
            log.error("监控项[{}]采集失败，保留旧快照: {}", cfg.getId(), e.getMessage());
        }
    }

    /**
     * 构造一条红盘告警事件负载
     */
    private Map<String, Object> buildAlert(AlertConfig cfg, long count, long nowSec) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("configId", cfg.getId());
        payload.put("title", cfg.getTitle() == null ? "" : cfg.getTitle());
        payload.put("logCount", count);
        payload.put("threshold", cfg.getAlertThreshold() == null ? 0 : cfg.getAlertThreshold());
        payload.put("status", HealthEvaluator.Status.RED.name());
        payload.put("time", nowSec * 1000L);
        return payload;
    }
}
