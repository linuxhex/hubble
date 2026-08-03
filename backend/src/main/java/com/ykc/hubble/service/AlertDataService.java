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
            String query = "level: ERROR | SELECT count(*) as cnt";
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
     * 按服务维度查看健康状态：先发现服务名，再逐个精确统计错误数，动态计算阈值
     */
    public List<Map<String, Object>> serviceHealth(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        String logstore = monitorProperties.getDefaultQueryLogstore();

        // 第一步：从100条原始日志中发现所有服务名
        List<LogEntry> logs;
        try {
            logs = slsQueryClient.queryLogstore(logstore, "level: ERROR", from, now, 0, 100);
        } catch (Exception e) {
            log.warn("查询服务健康状态失败: {}", e.getMessage());
            return new ArrayList<>();
        }
        
        List<String> serviceNames = new ArrayList<>();
        for (var entry : logs) {
            String service = (entry.getContainerName() != null && !entry.getContainerName().isBlank())
                    ? entry.getContainerName() : "unknown";
            if (service.startsWith("event-trac")) continue;
            if (!serviceNames.contains(service)) {
                serviceNames.add(service);
            }
        }

        if (serviceNames.isEmpty()) {
            return new ArrayList<>();
        }

        // 第二步：对每个服务精确查询错误总数（用 SLS analytics count）
        Map<String, Long> serviceCounts = new LinkedHashMap<>();
        long totalErrors = 0;
        for (String service : serviceNames) {
            long count = queryServiceErrorCount(logstore, service, from, now);
            serviceCounts.put(service, count);
            totalErrors += count;
        }

        // 第三步：动态计算阈值
        // 基于平均错误数：红盘 = 平均值 * 2，黄盘 = 平均值 * 0.8
        // 保证至少有一些服务能落在黄盘区间
        double avgErrors = serviceNames.isEmpty() ? 0 : (double) totalErrors / serviceNames.size();
        int redThreshold = Math.max((int) Math.ceil(avgErrors * 2), 3);
        int yellowThreshold = Math.max((int) Math.ceil(avgErrors * 0.8), 1);

        // 构建返回结果
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : serviceCounts.entrySet()) {
            String service = entry.getKey();
            long count = entry.getValue();

            String status;
            if (count >= redThreshold) {
                status = "RED";
            } else if (count >= yellowThreshold) {
                status = "YELLOW";
            } else {
                status = "GREEN";
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("serviceName", service);
            item.put("errorCount", count);
            item.put("redThreshold", redThreshold);
            item.put("yellowThreshold", yellowThreshold);
            item.put("status", status);
            result.add(item);
        }

        result.sort((a, b) -> Long.compare((Long) b.get("errorCount"), (Long) a.get("errorCount")));
        return result;
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
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        String logstore = monitorProperties.getDefaultQueryLogstore();
        long redTh = monitorProperties.getMinuteRedThreshold();
        long yellowTh = monitorProperties.getMinuteYellowThreshold();

        // 第一步：采样 100 条 ERROR 日志发现所有服务名
        List<String> serviceNames;
        try {
            List<LogEntry> sample = slsQueryClient.queryLogstore(logstore, "level: ERROR", from, now, 0, 100);
            serviceNames = new ArrayList<>();
            for (LogEntry entry : sample) {
                String svc = entry.getContainerName();
                if (svc != null && !svc.isBlank() && !svc.startsWith("event-trac") && !serviceNames.contains(svc)) {
                    serviceNames.add(svc);
                }
            }
        } catch (Exception e) {
            log.warn("采样服务名失败: {}", e.getMessage());
            serviceNames = Collections.emptyList();
        }

        if (serviceNames.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timeline", Collections.emptyList());
            result.put("minuteRedThreshold", redTh);
            result.put("minuteYellowThreshold", yellowTh);
            return result;
        }

        // 第二步：对每个服务用 analytics 查询每分钟错误数（服务名在 WHERE 中，避免 SELECT 字段引用问题）
        Map<String, Map<String, Long>> byMinute = new java.util.TreeMap<>();
        for (String service : serviceNames) {
            try {
                String query = "__tag__:_container_name_: " + service + " AND level: ERROR"
                        + " | SELECT date_format(__time__, '%Y-%m-%d %H:%i') as minute, count(*) as cnt GROUP BY minute";
                var rows = slsQueryClient.queryAnalytics(logstore, query, from, now, 1000);
                for (var row : rows) {
                    String minute = row.getOrDefault("minute", "");
                    long cnt = 0;
                    try { cnt = Long.parseLong(row.getOrDefault("cnt", "0")); } catch (NumberFormatException ignored) {}
                    if (cnt > 0) {
                        byMinute.computeIfAbsent(minute, k -> new LinkedHashMap<>())
                                .merge(service, cnt, Long::sum);
                    }
                }
            } catch (Exception e) {
                log.warn("查询服务分钟级数据失败: service={}, error={}", service, e.getMessage());
            }
        }

        // 第三步：每分钟独立判定
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (var e : byMinute.entrySet()) {
            long totalErrors = 0;
            List<Map<String, Object>> services = new ArrayList<>();
            String status = "NORMAL";
            for (var svcEntry : e.getValue().entrySet()) {
                long cnt = svcEntry.getValue();
                totalErrors += cnt;

                String svcStatus;
                if (cnt >= redTh) {
                    svcStatus = "RED";
                    status = "RED";
                } else if (cnt >= yellowTh) {
                    svcStatus = "YELLOW";
                    if (!"RED".equals(status)) {
                        status = "YELLOW";
                    }
                } else {
                    svcStatus = "GREEN";
                }

                Map<String, Object> svcItem = new LinkedHashMap<>();
                svcItem.put("name", svcEntry.getKey());
                svcItem.put("count", cnt);
                svcItem.put("status", svcStatus);
                services.add(svcItem);
            }
            services.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("minute", e.getKey());
            item.put("status", status);
            item.put("totalErrors", totalErrors);
            item.put("services", services);
            timeline.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeline", timeline);
        result.put("minuteRedThreshold", redTh);
        result.put("minuteYellowThreshold", yellowTh);
        return result;
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

}
