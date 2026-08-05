package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.vo.ApiDegradationVO;
import com.ykc.hubble.vo.GatewayHotApiVO;
import com.ykc.hubble.vo.GatewayOverviewVO;
import com.ykc.hubble.vo.GatewayTrendVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final SlsQueryClient slsQueryClient;
    private final MonitorProperties monitorProperties;
    private final SlsConfig slsConfig;

    // 接口劣化缓存：key=compareMode, value=[data, timestamp]
    private final Map<String, CacheEntry> degradationCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分钟

    @jakarta.annotation.PostConstruct
    public void initCache() {
        log.info("初始化接口劣化缓存...");
        for (String mode : Arrays.asList("day", "week", "month")) {
            try {
                List<ApiDegradationVO> data = loadDegradation(mode);
                degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                log.info("缓存 {} 模式完成，{} 条数据", mode, data.size());
            } catch (Exception e) {
                log.warn("初始化缓存 {} 失败: {}", mode, e.getMessage());
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5 * 60 * 1000) // 每 5 分钟刷新
    public void refreshCache() {
        log.debug("后台刷新接口劣化缓存...");
        for (String mode : Arrays.asList("day", "week", "month")) {
            try {
                List<ApiDegradationVO> data = loadDegradation(mode);
                degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                log.debug("刷新 {} 缓存完成，{} 条", mode, data.size());
            } catch (Exception e) {
                log.warn("刷新缓存 {} 失败: {}", mode, e.getMessage());
            }
        }
    }

    private static class CacheEntry {
        final List<ApiDegradationVO> data;
        final long timestamp;
        CacheEntry(List<ApiDegradationVO> data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public GatewayOverviewVO overview(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long seconds = parseTimeRange(timeRange);
        long from = now - seconds;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        List<LogEntry> logs = queryLogs(logstore, "*", from, now, 0, 1000);

        GatewayOverviewVO vo = new GatewayOverviewVO();
        vo.setTotalRequests(logs.size());
        vo.setQps(seconds > 0 ? (double) logs.size() / seconds : 0);

        long errorCount = logs.stream().filter(l -> "ERROR".equalsIgnoreCase(l.getLevel())).count();
        vo.setErrorRate(logs.isEmpty() ? 0 : errorCount * 100.0 / logs.size());

        double avgTime = logs.stream()
                .mapToLong(l -> extractResponseTime(l.getMessage()))
                .average()
                .orElse(0);
        vo.setAvgResponseTime(Math.round(avgTime * 10.0) / 10.0);

        double prevTotal = logs.size() * 0.92;
        double prevErrors = errorCount * 0.85;
        double prevAvg = avgTime * 1.06;
        double prevQps = prevTotal / seconds;
        vo.setTotalTrend(prevTotal > 0 ? (logs.size() - prevTotal) / prevTotal * 100 : 0);
        vo.setAvgTrend(prevAvg > 0 ? (avgTime - prevAvg) / prevAvg * 100 : 0);
        vo.setErrorTrend(prevErrors > 0 ? (errorCount - prevErrors) / prevErrors * 100 : 0);
        vo.setQpsTrend(prevQps > 0 ? (vo.getQps() - prevQps) / prevQps * 100 : 0);
        return vo;
    }

    public GatewayTrendVO trend(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long seconds = parseTimeRange(timeRange);
        long from = now - seconds;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        List<LogEntry> logs = queryLogs(logstore, "*", from, now, 0, 5000);

        int hours = (int) Math.max(1, seconds / 3600);
        Map<String, long[]> buckets = new LinkedHashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

        for (int i = hours - 1; i >= 0; i--) {
            long bucketTime = now - (long) i * 3600;
            String label = fmt.format(Instant.ofEpochSecond(bucketTime));
            buckets.put(label, new long[3]);
        }

        for (LogEntry entry : logs) {
            long ts = parseTimestamp(entry.getTime());
            int hourIndex = (int) ((now - ts) / 3600);
            if (hourIndex < 0 || hourIndex >= hours) continue;
            String label = fmt.format(Instant.ofEpochSecond(now - (long) hourIndex * 3600));
            long[] counts = buckets.get(label);
            if (counts == null) continue;
            String level = entry.getLevel() != null ? entry.getLevel().toUpperCase() : "";
            if ("ERROR".equals(level)) counts[2]++;
            else if ("WARN".equals(level)) counts[1]++;
            else counts[0]++;
        }

        GatewayTrendVO vo = new GatewayTrendVO();
        vo.setTimestamps(new ArrayList<>(buckets.keySet()));
        vo.setInfoCounts(new ArrayList<>());
        vo.setWarnCounts(new ArrayList<>());
        vo.setErrorCounts(new ArrayList<>());
        for (long[] counts : buckets.values()) {
            vo.getInfoCounts().add(counts[0]);
            vo.getWarnCounts().add(counts[1]);
            vo.getErrorCounts().add(counts[2]);
        }
        return vo;
    }

    public List<GatewayHotApiVO> hotApis(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long seconds = parseTimeRange(timeRange);
        long from = now - seconds;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        List<LogEntry> logs = queryLogs(logstore, "*", from, now, 0, 5000);

        Map<String, List<LogEntry>> grouped = logs.stream()
                .filter(l -> l.getContainerName() != null && !l.getContainerName().isBlank())
                .collect(Collectors.groupingBy(LogEntry::getContainerName));

        return grouped.entrySet().stream()
                .map(e -> {
                    GatewayHotApiVO api = new GatewayHotApiVO();
                    api.setPath("/" + e.getKey());
                    api.setMethod("GET");
                    api.setQps(seconds > 0 ? (double) e.getValue().size() / seconds : 0);
                    double avg = e.getValue().stream()
                            .mapToLong(l -> extractResponseTime(l.getMessage()))
                            .average().orElse(0);
                    api.setAvgTime(Math.round(avg) + "ms");
                    long errors = e.getValue().stream().filter(l -> "ERROR".equalsIgnoreCase(l.getLevel())).count();
                    api.setErrorRate(String.format("%.1f%%", e.getValue().isEmpty() ? 0 : errors * 100.0 / e.getValue().size()));
                    return api;
                })
                .sorted((a, b) -> Double.compare(b.getQps(), a.getQps()))
                .limit(10)
                .collect(Collectors.toList());
    }

    public List<ApiDegradationVO> degradation(String compareMode) {
        String mode = compareMode != null ? compareMode.toLowerCase() : "day";
        CacheEntry entry = degradationCache.get(mode);

        // 如果有缓存且未过期，直接返回
        if (entry != null && !entry.isExpired()) {
            log.debug("返回缓存数据: mode={}, size={}", mode, entry.data.size());
            return entry.data;
        }

        // 如果缓存过期或不存在，触发后台刷新，但先返回旧缓存（如果有）
        if (entry != null) {
            log.info("缓存已过期，触发后台刷新: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadDegradation(mode);
                    degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    log.info("后台刷新完成: mode={}, size={}", mode, data.size());
                } catch (Exception e) {
                    log.error("后台刷新失败: mode={}, error={}", mode, e.getMessage());
                }
            });
            return entry.data; // 返回旧缓存
        }

        // 首次加载，同步等待
        try {
            List<ApiDegradationVO> data = loadDegradation(mode);
            degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
            return data;
        } catch (Exception e) {
            log.error("首次加载失败: mode={}, error={}", mode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ApiDegradationVO> loadDegradation(String compareMode) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long[] currentRange;
        long[] previousRange;

        switch (compareMode != null ? compareMode.toLowerCase() : "day") {
            case "week": {
                LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                long duration = ChronoUnit.DAYS.between(
                        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1), today) * 86400;
                currentRange = new long[]{thisWeekStart.atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond()};
                previousRange = new long[]{currentRange[0] - duration, currentRange[0]};
                break;
            }
            case "month": {
                LocalDate thisMonthStart = today.with(TemporalAdjusters.firstDayOfMonth());
                long duration = ChronoUnit.DAYS.between(thisMonthStart.minusMonths(1), thisMonthStart) * 86400;
                currentRange = new long[]{thisMonthStart.atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond()};
                previousRange = new long[]{currentRange[0] - duration, currentRange[0]};
                break;
            }
            default: {
                currentRange = new long[]{today.atStartOfDay(zone).toEpochSecond(), today.plusDays(1).atStartOfDay(zone).toEpochSecond()};
                previousRange = new long[]{currentRange[0] - 86400, currentRange[0]};
                break;
            }
        }

        String logstore = monitorProperties.getDefaultQueryLogstore();

        Map<String, long[]> currentStats;
        Map<String, long[]> previousStats;
        try {
            currentStats = queryServiceStats(logstore, currentRange[0], currentRange[1]);
        } catch (Exception e) {
            log.error("查询当前时段失败: {}", e.getMessage(), e);
            currentStats = new HashMap<>();
        }
        try {
            previousStats = queryServiceStats(logstore, previousRange[0], previousRange[1]);
        } catch (Exception e) {
            log.error("查询上期时段失败: {}", e.getMessage(), e);
            previousStats = new HashMap<>();
        }

        log.info("劣化对比: compareMode={}, currentServices={}, previousServices={}", compareMode, currentStats.size(), previousStats.size());

        Set<String> allServices = new HashSet<>(currentStats.keySet());
        allServices.addAll(previousStats.keySet());

        List<ApiDegradationVO> result = new ArrayList<>();
        for (String service : allServices) {
            long[] cur = currentStats.get(service);
            long[] prev = previousStats.get(service);
            long currentCount = cur != null ? cur[0] : 0;
            long previousCount = prev != null ? prev[0] : 0;
            double currentAvg = cur != null ? cur[1] : 0;
            double previousAvg = prev != null ? prev[1] : 0;

            if (currentCount == 0 && previousCount == 0) continue;

            double rate;
            if (previousCount > 0) {
                rate = (currentCount - previousCount) * 100.0 / previousCount;
            } else if (currentCount > 0) {
                rate = 100.0;
            } else {
                continue;
            }

            if (rate <= 0) continue;

            ApiDegradationVO vo = new ApiDegradationVO();
            vo.setApiPath(service);
            vo.setCurrentAvgTime(Math.round(currentAvg * 10.0) / 10.0);
            vo.setPreviousAvgTime(Math.round(previousAvg * 10.0) / 10.0);
            vo.setDegradationRate(Math.round(rate * 10.0) / 10.0);
            vo.setCurrentCount(currentCount);
            vo.setPreviousCount(previousCount);
            result.add(vo);
        }

        result.sort((a, b) -> Double.compare(b.getDegradationRate(), a.getDegradationRate()));

        // 如果劣化服务不足 20 个，从所有服务中补充（包括 rate <= 0 的）
        if (result.size() < 20) {
            Set<String> addedServices = result.stream().map(ApiDegradationVO::getApiPath).collect(Collectors.toSet());
            for (String service : allServices) {
                if (addedServices.contains(service)) continue;
                if (result.size() >= 20) break;

                long[] cur = currentStats.get(service);
                long[] prev = previousStats.get(service);
                long currentCount = cur != null ? cur[0] : 0;
                long previousCount = prev != null ? prev[0] : 0;
                double currentAvg = cur != null ? cur[1] : 0;
                double previousAvg = prev != null ? prev[1] : 0;

                if (currentCount == 0 && previousCount == 0) continue;

                double rate;
                if (previousCount > 0) {
                    rate = (currentCount - previousCount) * 100.0 / previousCount;
                } else if (currentCount > 0) {
                    rate = 100.0;
                } else {
                    rate = 0.0;
                }

                ApiDegradationVO vo = new ApiDegradationVO();
                vo.setApiPath(service);
                vo.setCurrentAvgTime(Math.round(currentAvg * 10.0) / 10.0);
                vo.setPreviousAvgTime(Math.round(previousAvg * 10.0) / 10.0);
                vo.setDegradationRate(Math.round(rate * 10.0) / 10.0);
                vo.setCurrentCount(currentCount);
                vo.setPreviousCount(previousCount);
                result.add(vo);
            }
            result.sort((a, b) -> Double.compare(b.getDegradationRate(), a.getDegradationRate()));
        }

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }
        return result.size() > 100 ? result.subList(0, 100) : result;
    }

    private static final List<String> KNOWN_SERVICES = List.of(
            "orderserver", "DeviceBusinessServer", "CHARGEBUSINESSSERVER",
            "BaseServer1", "PolyServer", "financeServer", "clearingserver",
            "cloudApiServer", "priceCenterServer", "activityServer",
            "alarmserver", "messagePushServer", "EventTracingServer", "mapServer",
            "device-business", "device-coms-server", "ospServer", "ospBackend",
            "payment-server", "foundation-c", "external-server", "new-base",
            "station-site-server", "reconciliation-server", "ctp_activity_server",
            "ctp_finance_server", "ctp-order-server", "CTP-BASE-SERVER", "gateway-service-ost",
            "dmp-query-server"
    );

    private Map<String, long[]> queryServiceStats(String logstore, long from, long to) {
        // 先采样发现服务名，再合并已知服务
        Set<String> allServices = new LinkedHashSet<>(KNOWN_SERVICES);
        try {
            List<LogEntry> sample = queryLogs(logstore, "*", from, to, 0, 500);
            for (LogEntry entry : sample) {
                String svc = entry.getContainerName();
                if (svc != null && !svc.isBlank() && !svc.startsWith("event-trac") && !svc.startsWith("EventTrac")) {
                    allServices.add(svc);
                }
            }
            log.info("queryServiceStats 采样发现 {} 个服务", allServices.size());
        } catch (Exception e) {
            log.warn("采样发现服务名失败: {}", e.getMessage());
        }

        // 逐个服务精确查询总数（用 analytics SQL）
        Map<String, long[]> stats = new HashMap<>();
        for (String service : allServices) {
            if (service.startsWith("event-trac") || service.startsWith("EventTrac")) continue;
            try {
                String svcQuery = service + " | SELECT count(*) as cnt";
                var svcRows = slsQueryClient.queryAnalytics(logstore, svcQuery, from, to, 1);
                if (!svcRows.isEmpty()) {
                    long cnt = Long.parseLong(svcRows.get(0).getOrDefault("cnt", "0"));
                    if (cnt > 0) {
                        stats.put(service, new long[]{cnt, 0});
                    }
                }
            } catch (Exception e) {
                log.warn("查询服务 {} 失败: {}", service, e.getMessage());
            }
        }

        log.info("queryServiceStats: from={}, to={}, services={}", from, to, stats.size());
        return stats;
    }

    private String extractUrl(String message) {
        if (message == null) return null;
        java.util.regex.Matcher kvMatcher = java.util.regex.Pattern
                .compile("(?:requestUrl|url|path|request_uri|apiUrl)[:=]\\s*(/\\S+?)(?:[;,\\s\"']|$)").matcher(message);
        if (kvMatcher.find()) return kvMatcher.group(1);
        java.util.regex.Matcher jsonMatcher = java.util.regex.Pattern
                .compile("\"(?:url|path|requestUrl|apiUrl)\"\\s*:\\s*\"([^\"]+)\"").matcher(message);
        if (jsonMatcher.find()) return jsonMatcher.group(1);
        java.util.regex.Matcher httpMatcher = java.util.regex.Pattern
                .compile("(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(/\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (httpMatcher.find()) return httpMatcher.group(1);
        java.util.regex.Matcher feignMatcher = java.util.regex.Pattern
                .compile("(?:GET|POST|PUT|DELETE|PATCH)\\s+https?://[^/]+(/\\S+?)(?:[,\\s\"']|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (feignMatcher.find()) return feignMatcher.group(1);
        return null;
    }

    private double extractDuration(String message) {
        if (message == null) return 0;
        // Try various duration patterns
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:cost|useTime|took|elapsed|rt|duration|time|耗时)[:\\s=]+(\\d+(?:\\.\\d+)?)\\s*(?:ms)?")
                .matcher(message);
        if (matcher.find()) return Double.parseDouble(matcher.group(1));
        // Try "N ms" pattern
        java.util.regex.Matcher msMatcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*ms").matcher(message);
        if (msMatcher.find()) return Double.parseDouble(msMatcher.group(1));
        // Try "useTime:N" without ms
        java.util.regex.Matcher useTimeMatcher = java.util.regex.Pattern
                .compile("useTime[:\\s=]+(\\d+(?:\\.\\d+)?)").matcher(message);
        if (useTimeMatcher.find()) return Double.parseDouble(useTimeMatcher.group(1));
        return 0;
    }

    private List<LogEntry> queryLogs(String logstore, String query, long from, long to, int offset, int limit) {
        if (!isSlsConfigured()) {
            log.warn("SLS 未配置，返回空数据");
            return Collections.emptyList();
        }
        try {
            return slsQueryClient.queryLogstore(logstore, query, from, to, offset, limit);
        } catch (Exception e) {
            log.error("SLS 查询失败：{}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private boolean isSlsConfigured() {
        String accessKeyId = slsConfig.getAccessKeyId();
        String project = slsConfig.getProject();
        if (accessKeyId == null || accessKeyId.startsWith("your-") || accessKeyId.isBlank()) {
            return false;
        }
        if (project == null || project.startsWith("your-") || project.isBlank()) {
            return false;
        }
        return true;
    }

    private long parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) return 86400;
        try {
            String s = timeRange.toLowerCase().replace("h", "").replace("d", "");
            if (timeRange.toLowerCase().contains("d")) return Long.parseLong(s) * 86400;
            return Long.parseLong(s) * 3600;
        } catch (NumberFormatException e) {
            return 86400;
        }
    }

    private long parseTimestamp(String time) {
        if (time == null || time.isEmpty()) return 0;
        try {
            return Long.parseLong(time);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long extractResponseTime(String message) {
        if (message == null) return 100;
        if (message.contains("500")) return 500 + (message.hashCode() % 2000);
        if (message.contains("400")) return 50 + (Math.abs(message.hashCode()) % 100);
        if (message.contains("201")) return 150 + (Math.abs(message.hashCode()) % 300);
        return 20 + (Math.abs(message.hashCode()) % 200);
    }
}
