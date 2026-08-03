package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.entity.LogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TraceChainService {

    private final SlsQueryClient slsQueryClient;
    private final MonitorProperties monitorProperties;
    private final SlsConfig slsConfig;

    public Map<String, Object> queryTraceChain(String traceId, String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - parseTimeRange(timeRange);

        String logstore = monitorProperties.getDefaultQueryLogstore();
        // Try multiple query strategies, from most precise to most broad
        String[] queries = {
            "trace: " + traceId,
            "message: " + traceId,
            "__raw_log__: " + traceId,
            traceId
        };
        String[] queryLabels = {
            "trace字段",
            "message字段",
            "原始日志",
            "全文检索"
        };
        
        List<LogEntry> logs = Collections.emptyList();
        for (int i = 0; i < queries.length; i++) {
            String query = queries[i];
            log.info("尝试查询链路 [{}/{}]: 策略={}, traceId={}, query={}", 
                i + 1, queries.length, queryLabels[i], traceId, query);
            logs = queryLogs(logstore, query, from, now, 0, 1000);
            if (!logs.isEmpty()) {
                log.info("查询成功: 策略={}, traceId={}, 日志数量={}", queryLabels[i], traceId, logs.size());
                break;
            }
        }

        log.info("链路查询最终结果: traceId={}, 日志数量={}", traceId, logs.size());

        if (logs.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("traceId", traceId);
            result.put("nodes", Collections.emptyList());
            return result;
        }

        // 按服务分组
        Map<String, List<LogEntry>> logsByService = logs.stream()
                .filter(l -> l.getContainerName() != null && !l.getContainerName().isBlank())
                .collect(Collectors.groupingBy(LogEntry::getContainerName, LinkedHashMap::new, Collectors.toList()));

        // 构建节点列表
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map.Entry<String, List<LogEntry>>> serviceEntries = new ArrayList<>(logsByService.entrySet());

        for (int i = 0; i < serviceEntries.size(); i++) {
            Map.Entry<String, List<LogEntry>> entry = serviceEntries.get(i);
            String serviceName = entry.getKey();
            List<LogEntry> serviceLogs = entry.getValue();

            // 按时间排序
            serviceLogs.sort((a, b) -> {
                long timeA = parseTimestamp(a.getTime());
                long timeB = parseTimestamp(b.getTime());
                return Long.compare(timeA, timeB);
            });

            LogEntry firstLog = serviceLogs.get(0);
            LogEntry lastLog = serviceLogs.get(serviceLogs.size() - 1);

            long startTime = parseTimestamp(firstLog.getTime());
            long endTime = parseTimestamp(lastLog.getTime());

            // 计算耗时：与下一个服务的时间差
            long duration = 0;
            if (i < serviceEntries.size() - 1) {
                List<LogEntry> nextServiceLogs = serviceEntries.get(i + 1).getValue();
                nextServiceLogs.sort((a, b) -> Long.compare(parseTimestamp(a.getTime()), parseTimestamp(b.getTime())));
                long nextStartTime = parseTimestamp(nextServiceLogs.get(0).getTime());
                duration = nextStartTime - startTime;
            }

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("serviceName", serviceName);
            node.put("apiPath", extractApiPath(firstLog.getMessage()));
            node.put("timestamp", startTime);
            node.put("formattedTime", formatTimestamp(startTime));
            node.put("duration", duration);
            node.put("logCount", serviceLogs.size());
            node.put("status", determineStatus(serviceLogs));
            node.put("logs", serviceLogs.stream().map(this::logToMap).collect(Collectors.toList()));

            nodes.add(node);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("nodes", nodes);
        result.put("totalLogs", logs.size());
        return result;
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

    private long parseTimeRange(String timeRange) {
        if (timeRange == null) return 3600;
        return switch (timeRange) {
            case "15m" -> 900;
            case "30m" -> 1800;
            case "1h" -> 3600;
            case "6h" -> 21600;
            case "24h" -> 86400;
            default -> 3600;
        };
    }

    private long parseTimestamp(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return 0;
        try {
            long ts = Long.parseLong(timeStr);
            return ts < 10000000000L ? ts * 1000 : ts;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) return "";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }

    private String extractApiPath(String message) {
        if (message == null || message.isBlank()) return "";

        // 尝试多种模式提取URL
        java.util.regex.Pattern[] patterns = {
                java.util.regex.Pattern.compile("(?:requestUrl|url|path|request_uri|apiUrl)[:=]\\s*(/\\S+?)(?:[;,\\s\"']|$)"),
                java.util.regex.Pattern.compile("\"(?:url|path|requestUrl|apiUrl)\"\\s*:\\s*\"([^\"]+)\""),
                java.util.regex.Pattern.compile("(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(/\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("(?:GET|POST|PUT|DELETE|PATCH)\\s+https?://[^/]+(/\\S+?)(?:[,\\s\"']|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
        };

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(message);
            if (matcher.find()) return matcher.group(1);
        }

        return "";
    }

    private String determineStatus(List<LogEntry> logs) {
        for (LogEntry log : logs) {
            String level = log.getLevel();
            if (level != null && level.equalsIgnoreCase("ERROR")) {
                return "error";
            }
        }
        return "success";
    }

    private Map<String, Object> logToMap(LogEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", entry.getTime());
        map.put("formattedTime", formatTimestamp(parseTimestamp(entry.getTime())));
        map.put("level", entry.getLevel());
        map.put("message", entry.getMessage());
        map.put("trace", entry.getTrace());
        map.put("containerName", entry.getContainerName());
        map.put("containerIp", entry.getContainerIp());
        if (entry.getFields() != null) {
            map.put("fields", entry.getFields());
        }
        return map;
    }

    private boolean isSlsConfigured() {
        String accessKeyId = slsConfig.getAccessKeyId();
        String project = slsConfig.getProject();
        if (accessKeyId == null || accessKeyId.startsWith("your-") || accessKeyId.isBlank()) return false;
        if (project == null || project.startsWith("your-") || project.isBlank()) return false;
        return true;
    }
}
