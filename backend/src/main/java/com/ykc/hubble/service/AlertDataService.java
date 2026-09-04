package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.util.TimeRanges;
import com.ykc.hubble.vo.AlertStatisticVO;
import com.ykc.hubble.vo.SnapshotPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 监控数据服务：优先从快照缓存计算统计，缓存为空时直接查 SLS。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertDataService {

    private final AlertConfigService alertConfigService;
    private final SnapshotCache snapshotCache;
    private final SlsQueryClient slsQueryClient;
    private final MonitorProperties monitorProperties;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final java.util.concurrent.Executor queryExecutor;

    // 异常大盘分钟级时间线缓存：key=timeRange, value=[data, timestamp]
    private final Map<String, TimelineCacheEntry> timelineCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TIMELINE_CACHE_TTL_MS = 30 * 1000; // 30 秒

    @jakarta.annotation.PostConstruct
    public void initTimelineCache() {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            log.info("异步初始化异常大盘时间线缓存...");
            for (String range : java.util.Arrays.asList("15m", "1h", "6h", "24h")) {
                try {
                    Map<String, Object> data = loadMinuteHealthTimeline(range);
                    timelineCache.put(range, new TimelineCacheEntry(data, System.currentTimeMillis()));
                    log.info("缓存 {} 时间线完成", range);
                } catch (Exception e) {
                    log.warn("初始化缓存 {} 失败: {}", range, e.getMessage());
                }
            }
        }, queryExecutor);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30 * 1000) // 每 30 秒刷新
    public void refreshTimelineCache() {
        log.debug("后台刷新异常大盘时间线缓存...");
        for (String range : java.util.Arrays.asList("15m", "1h", "6h", "24h")) {
            try {
                Map<String, Object> data = loadMinuteHealthTimeline(range);
                timelineCache.put(range, new TimelineCacheEntry(data, System.currentTimeMillis()));
                log.debug("刷新 {} 时间线缓存完成", range);
            } catch (Exception e) {
                log.warn("刷新缓存 {} 失败: {}", range, e.getMessage());
            }
        }
    }

    private static class TimelineCacheEntry {
        final Map<String, Object> data;
        final long timestamp;
        TimelineCacheEntry(Map<String, Object> data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TIMELINE_CACHE_TTL_MS;
        }
    }

    /**
     * 统计：当前值 / 今日峰值 / 均值 / 告警计数 / 健康度
     */
    public AlertStatisticVO statistics(Long configId, String timeRange) {
        AlertConfig cfg = alertConfigService.detail(configId);
        long now = System.currentTimeMillis() / 1000;
        Integer threshold = cfg.getAlertThreshold();

        // 始终直接查询 SLS 获取当前值，确保数据实时
        long currentLogCount = querySlsCount(timeRange);

        long todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        List<SnapshotPoint> todayPoints = snapshotCache.get(configId, todayStart, now);

        long todayMax = currentLogCount;
        long sum = currentLogCount;
        long todayAlertCount = 0;
        
        if (!todayPoints.isEmpty()) {
            todayMax = currentLogCount;
            sum = 0;
            for (SnapshotPoint p : todayPoints) {
                todayMax = Math.max(todayMax, p.getLogCount());
                sum += p.getLogCount();
                if (threshold != null && threshold > 0 && p.getLogCount() >= threshold) {
                    todayAlertCount++;
                }
            }
            sum += currentLogCount;
        }
        
        if (threshold != null && threshold > 0 && currentLogCount >= threshold) {
            todayAlertCount++;
        }
        
        double todayAvg = todayPoints.isEmpty() ? currentLogCount : (sum * 1.0 / (todayPoints.size() + 1));

        AlertStatisticVO vo = new AlertStatisticVO();
        vo.setCurrentLogCount(currentLogCount);
        vo.setTodayMax(todayMax);
        vo.setTodayAvg(todayAvg);
        vo.setAlertThreshold(threshold);
        vo.setTodayAlertCount(todayAlertCount);
        HealthEvaluator.Status status = HealthEvaluator.evaluate(currentLogCount, threshold);
        vo.setStatus(status.name());
        vo.setDetailStatistics(buildDetailStatistics(todayPoints, threshold));
        return vo;
    }

    private long querySlsCount(String timeRange) {
        try {
            long now = System.currentTimeMillis() / 1000;
            long from = now - TimeRanges.toSeconds(timeRange);
            String logstore = monitorProperties.getDefaultQueryLogstore();
            String query = "* | SELECT count(*) as cnt";
            var rows = slsQueryClient.queryAnalytics(logstore, query, from, now, 1);
            long count = 0;
            if (!rows.isEmpty()) {
                try {
                    count = Long.parseLong(rows.get(0).getOrDefault("cnt", "0"));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            log.info("querySlsCount: logstore={}, timeRange={}, count={}", logstore, timeRange, count);
            return count;
        } catch (Exception e) {
            log.warn("直接查询SLS失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 时序明细：返回 timeRange 内的采集点（collectedAt 用毫秒，便于前端直接作为时间戳）
     */
    public Map<String, Object> query(Long configId, String timeRange, int current, int size) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        List<SnapshotPoint> points = snapshotCache.get(configId, from, now);

        List<Map<String, Object>> all = new ArrayList<>();
        for (SnapshotPoint p : points) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("collectedAt", p.getCollectedAt() * 1000L);
            item.put("logCount", p.getLogCount());
            all.add(item);
        }

        int total = all.size();
        int fromIdx = Math.min(Math.max(0, (current - 1) * size), total);
        int toIdx = Math.min(fromIdx + size, total);

        Map<String, Object> result = new HashMap<>();
        result.put("records", all.subList(fromIdx, toIdx));
        result.put("total", total);
        return result;
    }

    /**
     * P0 分项：按健康度把今日采集点归类计数
     */
    private List<Map<String, Object>> buildDetailStatistics(List<SnapshotPoint> points, Integer threshold) {
        int normal = 0, yellow = 0, red = 0;
        for (SnapshotPoint p : points) {
            HealthEvaluator.Status s = HealthEvaluator.evaluate(p.getLogCount(), threshold);
            switch (s) {
                case NORMAL -> normal++;
                case YELLOW -> yellow++;
                case RED -> red++;
                default -> {
                }
            }
        }
        List<Map<String, Object>> detail = new ArrayList<>();
        detail.add(detailItem("NORMAL", normal));
        detail.add(detailItem("YELLOW", yellow));
        detail.add(detailItem("RED", red));
        return detail;
    }

    private Map<String, Object> detailItem(String status, int count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("status", status);
        item.put("count", count);
        return item;
    }

    /**
     * 按服务维度查看健康状态：基于 alert_config 配置项，使用动态阈值（基于历史数据）
     */
    public List<Map<String, Object>> serviceHealth(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        List<AlertConfig> configs = alertConfigService.listEnabled();
        if (configs.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (AlertConfig cfg : configs) {
            String serviceName = templateIdToServiceName(cfg.getKeywordTemplateId());
            String keywords = templateIdToKeywords(cfg.getKeywordTemplateId());

            int interval = cfg.getCollectionInterval() != null ? cfg.getCollectionInterval() : 60;
            long from = now - interval;

            // 查询当前错误数
            long count = 0;
            try {
                String query = keywords + " | SELECT count(*) as cnt";
                var rows = slsQueryClient.queryAnalytics(logstore, query, from, now, 1);
                if (!rows.isEmpty()) {
                    try { count = Long.parseLong(rows.get(0).getOrDefault("cnt", "0")); }
                    catch (NumberFormatException ignored) {}
                }
            } catch (Exception e) {
                log.warn("查询服务错误数失败: service={}, error={}", serviceName, e.getMessage());
            }

            // 计算动态阈值：基于过去 7 天同时段数据的平均值
            int[] thresholds = calculateDynamicThreshold(logstore, keywords, interval, now);
            int redThreshold = thresholds[0];
            int yellowThreshold = thresholds[1];

            String status;
            if (count >= redThreshold) {
                status = "RED";
            } else if (count >= yellowThreshold) {
                status = "YELLOW";
            } else {
                status = "GREEN";
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("serviceName", serviceName);
            item.put("errorCount", count);
            item.put("redThreshold", redThreshold);
            item.put("yellowThreshold", yellowThreshold);
            item.put("status", status);
            item.put("configId", cfg.getId());
            result.add(item);
        }

        result.sort((a, b) -> Long.compare((Long) b.get("errorCount"), (Long) a.get("errorCount")));
        return result;
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
            return new int[]{redThreshold, yellowThreshold};
        }

        // 如果历史数据不可用，使用配置的默认阈值
        return new int[]{100, 50};
    }

    private String templateIdToServiceName(String templateId) {
        if (templateId == null) return "unknown";
        if (templateId.contains("statistics-server")) return "statistics-server";
        if (templateId.contains("statistics-tob")) return "statistics-tob";
        if (templateId.contains("trade-order")) return "trade-order";
        if (templateId.contains("device-maint")) return "device-maint";
        if (templateId.contains("zdl-push")) return "zdl-push-server";
        if (templateId.contains("tpl-stat")) return "statistics-server";
        if (templateId.contains("tpl-tob")) return "statistics-tob";
        if (templateId.contains("tpl-order")) return "trade-order";
        if (templateId.contains("tpl-device")) return "device-maint";
        if (templateId.contains("tpl-push")) return "zdl-push-server";
        return templateId;
    }

    private String templateIdToKeywords(String templateId) {
        if (templateId == null) return "level: ERROR";
        if (templateId.contains("statistics-server"))
            return "__tag__:_container_name_: statistics-server AND level: ERROR";
        if (templateId.contains("statistics-tob"))
            return "__tag__:_container_name_: statistics-tob AND level: ERROR";
        if (templateId.contains("trade-order"))
            return "__tag__:_container_name_: trade-order AND level: ERROR";
        if (templateId.contains("device-maint"))
            return "__tag__:_container_name_: device-maint AND level: ERROR";
        if (templateId.contains("zdl-push"))
            return "__tag__:_container_name_: zdl-push-server AND level: ERROR";
        if (templateId.contains("tpl-stat"))
            return "__tag__:_container_name_: statistics-server AND level: ERROR";
        if (templateId.contains("tpl-tob"))
            return "__tag__:_container_name_: statistics-tob AND level: ERROR";
        if (templateId.contains("tpl-order"))
            return "__tag__:_container_name_: trade-order AND level: ERROR";
        if (templateId.contains("tpl-device"))
            return "__tag__:_container_name_: device-maint AND level: ERROR";
        if (templateId.contains("tpl-push"))
            return "__tag__:_container_name_: zdl-push-server AND level: ERROR";
        return "level: ERROR";
    }

    private long queryServiceErrorCount(String logstore, String serviceName, long from, long to) {
        try {
            String query = "__tag__:_container_name_: " + serviceName + " AND level: ERROR | SELECT count(*) as cnt";
            var rows = slsQueryClient.queryAnalytics(logstore, query, from, to, 1);
            if (!rows.isEmpty()) {
                try {
                    return Long.parseLong(rows.get(0).getOrDefault("cnt", "0"));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        } catch (Exception e) {
            log.warn("查询服务错误数失败: service={}, error={}", serviceName, e.getMessage());
        }
        return 0;
    }

    /**
     * 分钟级健康时间线：先采样发现服务名，再逐个服务用 analytics 查询每分钟错误数，
     * 每分钟每服务独立对比当前阈值，worst-wins 到分钟。
     */
    public Map<String, Object> minuteHealthTimeline(String timeRange) {
        String range = timeRange != null ? timeRange : "15m";
        TimelineCacheEntry entry = timelineCache.get(range);

        Map<String, Object> data;

        if (entry != null && !entry.isExpired()) {
            log.debug("返回缓存时间线数据: range={}", range);
            data = entry.data;
        } else {
            if (entry == null || System.currentTimeMillis() - entry.timestamp > TIMELINE_CACHE_TTL_MS * 2) {
                log.info("时间线缓存过期，异步刷新：range={}", range);
                CompletableFuture.runAsync(() -> {
                    try {
                        Map<String, Object> freshData = loadMinuteHealthTimeline(range);
                        timelineCache.put(range, new TimelineCacheEntry(freshData, System.currentTimeMillis()));
                    } catch (Exception e) {
                        log.error("异步刷新时间线缓存失败：range={}, error={}", range, e.getMessage());
                    }
                });
            }

            if (entry != null) {
                data = entry.data;
            } else {
                Map<String, Object> emptyResult = new LinkedHashMap<>();
                emptyResult.put("timeline", Collections.emptyList());
                return emptyResult;
            }
        }

        return extendTimelineToCurrentTime(data);
    }

    /**
     * 将时间线末尾延伸到当前分钟：若缓存中最后一条数据早于当前时间，
     * 用最后一条的数据填充到当前分钟，保证展示时间与真实时间一致。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extendTimelineToCurrentTime(Map<String, Object> data) {
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) data.get("timeline");
        if (timeline == null || timeline.isEmpty()) {
            return data;
        }

        java.time.format.DateTimeFormatter minuteFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        java.time.LocalDateTime currentMinute = java.time.LocalDateTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0);
        String currentMinuteStr = currentMinute.format(minuteFmt);

        String lastMinuteStr = (String) timeline.get(timeline.size() - 1).get("minute");
        if (lastMinuteStr == null || lastMinuteStr.compareTo(currentMinuteStr) >= 0) {
            return data;
        }

        Map<String, Object> lastPoint = timeline.get(timeline.size() - 1);
        List<Map<String, Object>> lastServices = (List<Map<String, Object>>) lastPoint.get("services");
        String lastStatus = (String) lastPoint.get("status");
        Object lastTotalErrors = lastPoint.get("totalErrors");

        java.time.LocalDateTime lastMinuteDt = java.time.LocalDateTime.parse(lastMinuteStr, minuteFmt);

        List<Map<String, Object>> extendedTimeline = new ArrayList<>(timeline);
        for (java.time.LocalDateTime m = lastMinuteDt.plusMinutes(1); !m.isAfter(currentMinute); m = m.plusMinutes(1)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("minute", m.format(minuteFmt));
            item.put("status", lastStatus);
            item.put("totalErrors", lastTotalErrors);
            item.put("services", lastServices);
            extendedTimeline.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeline", extendedTimeline);
        return result;
    }

    private Map<String, Object> loadMinuteHealthTimeline(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        String logstore = monitorProperties.getDefaultQueryLogstore();

        List<String> serviceNames = new ArrayList<>();
        // 分页采样发现服务名（SLS 每次最多返回 100 条，需要分页）
        try {
            int pageSize = 100;
            int maxPages = 10;
            for (int page = 0; page < maxPages; page++) {
                List<LogEntry> sample = slsQueryClient.queryLogstore(logstore, "level: ERROR", from, now, page * pageSize, pageSize);
                if (sample == null || sample.isEmpty()) break;
                for (LogEntry entry : sample) {
                    String svc = entry.getContainerName();
                    if (svc != null && !svc.isBlank() && !svc.startsWith("event-trac") && !serviceNames.contains(svc)) {
                        serviceNames.add(svc);
                    }
                }
                if (sample.size() < pageSize) break;
            }
            log.info("采样发现 {} 个服务", serviceNames.size());
        } catch (Exception e) {
            log.warn("采样服务名失败: {}", e.getMessage());
        }

        if (serviceNames.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timeline", Collections.emptyList());
            return result;
        }

        // 每个服务：查分钟级错误数 + 计算动态阈值
        java.util.TreeMap<String, Map<String, Long>> byMinute = new java.util.TreeMap<>();
        Map<String, ServiceThresholds> thresholdsMap = new HashMap<>();

        for (String service : serviceNames) {
            try {
                String query = "__tag__:_container_name_: " + service + " AND level: ERROR"
                        + " | SELECT date_format(__time__, '%Y-%m-%d %H:%i') as minute, count(*) as cnt GROUP BY minute";
                var rows = slsQueryClient.queryAnalytics(logstore, query, from, now, 1000);

                long totalErrors = 0;
                int minuteCount = 0;
                for (var row : rows) {
                    String minute = row.getOrDefault("minute", "");
                    long cnt = 0;
                    try { cnt = Long.parseLong(row.getOrDefault("cnt", "0")); } catch (NumberFormatException ignored) {}
                    if (cnt > 0) {
                        totalErrors += cnt;
                        minuteCount++;
                        byMinute.computeIfAbsent(minute, k -> new LinkedHashMap<>())
                                .merge(service, cnt, Long::sum);
                    }
                }

                double avgPerMin = minuteCount > 0 ? (double) totalErrors / minuteCount : 0;
                int redTh = Math.max((int) Math.ceil(avgPerMin * 6), 5);
                int yellowTh = Math.max((int) Math.ceil(avgPerMin * 3), 2);
                thresholdsMap.put(service, new ServiceThresholds(redTh, yellowTh));

            } catch (Exception e) {
                log.warn("查询服务分钟级数据失败: service={}, error={}", service, e.getMessage());
            }
        }

        // 构建完整时间线（覆盖所有分钟，无数据的填 0）
        List<Map<String, Object>> timeline = new ArrayList<>();
        java.time.format.DateTimeFormatter minuteFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        java.time.LocalDateTime startMinute = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(from), java.time.ZoneId.systemDefault()).withSecond(0).withNano(0);
        java.time.LocalDateTime endMinute = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(now), java.time.ZoneId.systemDefault()).withSecond(0).withNano(0);

        // 找到 SLS 实际返回的最后有数据分钟
        String lastDataMinute = byMinute.isEmpty() ? null : byMinute.lastKey();

        for (java.time.LocalDateTime minute = startMinute; !minute.isAfter(endMinute); minute = minute.plusMinutes(1)) {
            String minuteKey = minute.format(minuteFmt);
            // SLS 数据缺失的分钟，用最后有数据的分钟填充
            Map<String, Long> minuteData = byMinute.getOrDefault(minuteKey,
                    lastDataMinute != null && minuteKey.compareTo(lastDataMinute) > 0
                            ? byMinute.get(lastDataMinute)
                            : Collections.emptyMap());
            if (minuteData == null) minuteData = Collections.emptyMap();

            long totalErrors = 0;
            List<Map<String, Object>> services = new ArrayList<>();
            String status = "NORMAL";

            for (String service : serviceNames) {
                long cnt = minuteData.getOrDefault(service, 0L);
                if (cnt == 0) continue;

                totalErrors += cnt;
                ServiceThresholds th = thresholdsMap.getOrDefault(service, new ServiceThresholds(5, 2));

                String svcStatus;
                if (cnt >= th.redThreshold) {
                    svcStatus = "RED";
                    status = "RED";
                } else if (cnt >= th.yellowThreshold) {
                    svcStatus = "YELLOW";
                    if (!"RED".equals(status)) status = "YELLOW";
                } else {
                    svcStatus = "GREEN";
                }

                Map<String, Object> svcItem = new LinkedHashMap<>();
                svcItem.put("name", service);
                svcItem.put("count", cnt);
                svcItem.put("status", svcStatus);
                svcItem.put("redThreshold", th.redThreshold);
                svcItem.put("yellowThreshold", th.yellowThreshold);
                services.add(svcItem);
            }
            services.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("minute", minuteKey);
            item.put("status", status);
            item.put("totalErrors", totalErrors);
            item.put("services", services);
            timeline.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeline", timeline);
        return result;
    }

    private long queryWarnCount(String logstore, String serviceName, long from, long to) {
        try {
            String query = "__tag__:_container_name_: " + serviceName + " AND level: WARN | SELECT count(*) as cnt";
            var rows = slsQueryClient.queryAnalytics(logstore, query, from, to, 1);
            if (!rows.isEmpty()) {
                try { return Long.parseLong(rows.get(0).getOrDefault("cnt", "0")); } catch (NumberFormatException e) { return 0; }
            }
        } catch (Exception e) {
            log.warn("查询WARN数失败: service={}, error={}", serviceName, e.getMessage());
        }
        return 0;
    }

    private static class ServiceThresholds {
        final int redThreshold;
        final int yellowThreshold;
        ServiceThresholds(int red, int yellow) { this.redThreshold = red; this.yellowThreshold = yellow; }
    }

    private long queryServiceTypeCount(String logstore, String serviceName, String keyword, long from, long to) {
        try {
            String query = "__tag__:_container_name_: " + serviceName + " AND level: ERROR AND " + keyword + " | SELECT count(*) as cnt";
            var rows = slsQueryClient.queryAnalytics(logstore, query, from, to, 1);
            if (!rows.isEmpty()) {
                try {
                    return Long.parseLong(rows.get(0).getOrDefault("cnt", "0"));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        } catch (Exception e) {
            log.warn("查询服务错误类型数失败: service={}, keyword={}, error={}", serviceName, keyword, e.getMessage());
        }
        return 0;
    }

    /**
     * 服务下钻：错误日志 + 异常分类 + 下游依赖
     */
    public Map<String, Object> serviceDrillDown(String serviceName, String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        String logstore = monitorProperties.getDefaultQueryLogstore();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceName", serviceName);

        // 1. 查询最近20条 ERROR 日志
        List<Map<String, Object>> errorLogs = new ArrayList<>();
        try {
            List<LogEntry> logs = slsQueryClient.queryLogstore(logstore,
                    "__tag__:_container_name_: " + serviceName + " AND level: ERROR",
                    from, now, 0, 20);
            for (LogEntry entry : logs) {
                Map<String, Object> logItem = new LinkedHashMap<>();
                logItem.put("time", entry.getTime());
                logItem.put("level", entry.getLevel());
                logItem.put("message", truncateMessage(entry.getMessage(), 500));
                logItem.put("traceId", entry.getTrace());
                logItem.put("logger", entry.getFields() != null ? entry.getFields().get("logger") : null);
                errorLogs.add(logItem);
            }
        } catch (Exception e) {
            log.warn("查询服务错误日志失败: service={}, error={}", serviceName, e.getMessage());
        }
        result.put("errorLogs", errorLogs);

        // 2. 异常分类统计
        Map<String, Object> breakdown = new LinkedHashMap<>();
        long npeCount = queryServiceTypeCount(logstore, serviceName, "NullPointerException", from, now);
        long timeoutCount = queryServiceTypeCount(logstore, serviceName,
                "(Timeout OR SocketTimeout OR \"Connection timed out\")", from, now);
        long warnCount = queryWarnCount(logstore, serviceName, from, now);
        long totalErrors = queryServiceErrorCount(logstore, serviceName, from, now);
        long otherCount = Math.max(0, totalErrors - npeCount - timeoutCount);
        breakdown.put("npe", npeCount);
        breakdown.put("timeout", timeoutCount);
        breakdown.put("warn", warnCount);
        breakdown.put("other", otherCount);
        breakdown.put("total", totalErrors);
        result.put("errorBreakdown", breakdown);

        // 3. 提取下游依赖（从日志 message 中匹配 Feign/RestTemplate 调用）
        List<String> downstreamServices = new ArrayList<>();
        java.util.regex.Pattern feignPattern = java.util.regex.Pattern.compile(
                "(?:POST|GET|PUT|DELETE|PATCH)\\s+https?://([a-zA-Z0-9_-]+)/",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern servicePattern = java.util.regex.Pattern.compile(
                "(?:Calling|Invoking|FeignClient|restTemplate)\\s+([a-zA-Z0-9_-]+(?:-server|-prod|-uat))",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String msg : errorLogs.stream()
                .map(l -> l.get("message") != null ? l.get("message").toString() : "")
                .toList()) {
            if (msg == null || msg.isBlank()) continue;
            var m1 = feignPattern.matcher(msg);
            if (m1.find()) {
                String ds = m1.group(1);
                if (!downstreamServices.contains(ds)) downstreamServices.add(ds);
            }
            var m2 = servicePattern.matcher(msg);
            if (m2.find()) {
                String ds = m2.group(1);
                if (!downstreamServices.contains(ds)) downstreamServices.add(ds);
            }
        }
        result.put("downstreamServices", downstreamServices);

        return result;
    }

    private String truncateMessage(String message, int maxLen) {
        if (message == null) return "";
        return message.length() > maxLen ? message.substring(0, maxLen) + "..." : message;
    }

}
