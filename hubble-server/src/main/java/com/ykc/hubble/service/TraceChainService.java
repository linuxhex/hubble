package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.entity.LogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
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
    private final PageDataCacheService pageDataCacheService;

    @Value("${trace.gateway-container:}")
    private String gatewayContainer;

    @Value("${trace.fallback-services:}")
    private String fallbackServicesStr;

    private String[] fallbackServices = new String[0];

    @PostConstruct
    public void init() {
        if (fallbackServicesStr != null && !fallbackServicesStr.isBlank()) {
            fallbackServices = Arrays.stream(fallbackServicesStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        log.info("链路追踪配置加载完成: gatewayContainer={}, {} 个备用服务",
                gatewayContainer, fallbackServices.length);
    }

    public List<Map<String, Object>> searchTracesByApi(String apiPath, String timeRange, int limit) {
        String pageKey = "trace_search";
        String dataKey = apiPath + "_" + timeRange + "_" + limit;
        
        // 检查数据库缓存
        String cachedJson = pageDataCacheService.getRaw(pageKey, dataKey);
        if (cachedJson != null) {
            try {
                var listType = new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {};
                List<Map<String, Object>> dbCached = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cachedJson, listType);
                log.info("返回链路搜索数据库缓存: apiPath={}, size={}", apiPath, dbCached.size());
                return dbCached;
            } catch (Exception e) {
                log.warn("解析链路搜索缓存失败: {}", e.getMessage());
            }
        }
        
        // 缓存未命中，查询并缓存
        List<Map<String, Object>> result = loadTracesByApi(apiPath, timeRange, limit);
        pageDataCacheService.save(pageKey, dataKey, result);
        return result;
    }
    
    private List<Map<String, Object>> loadTracesByApi(String apiPath, String timeRange, int limit) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - parseTimeRange(timeRange);

        String logstore = monitorProperties.getDefaultQueryLogstore();
        if (!isSlsConfigured()) {
            log.warn("SLS 未配置，返回空数据");
            return Collections.emptyList();
        }

        String serviceNameTemp = apiPath;
        if (apiPath != null && apiPath.contains("/")) {
            // Remove leading "/" if present
            String path = apiPath.startsWith("/") ? apiPath.substring(1) : apiPath;
            if (path.contains("/")) {
                serviceNameTemp = path.substring(0, path.indexOf('/'));
            } else {
                serviceNameTemp = path;
            }
        }
        // Strip -prod suffix to match SLS container names
        if (serviceNameTemp.endsWith("-prod")) {
            serviceNameTemp = serviceNameTemp.substring(0, serviceNameTemp.length() - 5);
        }
        // Normalize camelCase to hyphen-case (e.g., orderServer -> order-server)
        serviceNameTemp = serviceNameTemp.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
        final String serviceName = serviceNameTemp;

        try {
            log.info("搜索链路: apiPath={}, serviceName={}, timeRange={}", apiPath, serviceName, timeRange);
            String query = "__tag__:_container_name_: " + serviceName;
            List<LogEntry> logs = slsQueryClient.queryLogstore(logstore, query, from, now, 0, 2000);
            log.info("查询到 {} 条日志", logs.size());
            if (logs.isEmpty()) {
                return Collections.emptyList();
            }

            long logsWithTrace = logs.stream().filter(l -> getTraceId(l) != null).count();
            log.info("其中 {} 条日志有 traceId", logsWithTrace);

            // 如果当前服务没有 trace 字段，尝试从网关日志中查找
            if (logsWithTrace == 0) {
                log.info("服务 {} 无 trace 字段，尝试从网关日志查找", serviceName);
                
                // 提取 API 路径的不同部分用于搜索
                String fullPath = apiPath; // e.g., "external-server/hlht/notification_charge_order_info"
                String pathWithoutService = fullPath; // e.g., "/hlht/notification_charge_order_info"
                String endpointOnly = fullPath; // e.g., "notification_charge_order_info"
                
                if (fullPath.contains("/")) {
                    String path = fullPath.startsWith("/") ? fullPath.substring(1) : fullPath;
                    if (path.contains("/")) {
                        pathWithoutService = path.substring(path.indexOf('/'));
                        String[] parts = path.split("/");
                        endpointOnly = parts[parts.length - 1];
                    }
                }
                
                // 尝试多种查询策略
                String[] gatewayQueries = {
                    "__tag__:_container_name_: " + gatewayContainer + " and " + endpointOnly,
                    "__tag__:_container_name_: " + gatewayContainer + " and " + serviceName,
                    "__tag__:_container_name_: " + gatewayContainer
                };
                String[] queryLabels = {
                    "端点名(" + endpointOnly + ")",
                    "服务名(" + serviceName + ")",
                    "全量网关日志"
                };
                
                List<LogEntry> gatewayLogs = Collections.emptyList();
                for (int i = 0; i < gatewayQueries.length; i++) {
                    String gq = gatewayQueries[i];
                    List<LogEntry> tempLogs = slsQueryClient.queryLogstore(logstore, gq, from, now, 0, 2000);
                    long tempWithTrace = tempLogs.stream().filter(l -> getTraceId(l) != null).count();
                    log.info("网关查询策略[{}]: {} → {} 条日志，{} 条有 traceId", 
                        i + 1, queryLabels[i], tempLogs.size(), tempWithTrace);
                    
                    if (tempWithTrace > 0) {
                        gatewayLogs = tempLogs;
                        logsWithTrace = tempWithTrace;
                        log.info("使用策略[{}]成功", i + 1);
                        break;
                    }
                }
                
                if (!gatewayLogs.isEmpty() && logsWithTrace > 0) {
                    logs = gatewayLogs;
                }
            }

            Map<String, List<LogEntry>> byTrace = logs.stream()
                    .filter(l -> getTraceId(l) != null)
                    .collect(Collectors.groupingBy(l -> getTraceId(l), LinkedHashMap::new, Collectors.toList()));
            
            log.info("提取到 {} 个链路", byTrace.size());
            
            // Log sample time values for debugging
            if (!logs.isEmpty()) {
                LogEntry sample = logs.get(0);
                log.info("样本日志 - time: {}, trace: {}, container: {}, fields: {}", 
                    sample.getTime(), getTraceId(sample), sample.getContainerName(),
                    sample.getFields() != null ? sample.getFields().keySet() : "null");
            }

            return byTrace.entrySet().stream()
                    .map(e -> {
                        String traceId = e.getKey();
                        List<LogEntry> traceLogs = e.getValue();
                        traceLogs.sort((a, b) -> Long.compare(parseTimestamp(a.getTime()), parseTimestamp(b.getTime())));

                        long firstTime = parseTimestamp(traceLogs.get(0).getTime());
                        long lastTime = parseTimestamp(traceLogs.get(traceLogs.size() - 1).getTime());
                        long duration = lastTime - firstTime;

                        Set<String> services = traceLogs.stream()
                                .map(LogEntry::getContainerName)
                                .filter(s -> s != null && !s.isBlank())
                                .collect(Collectors.toCollection(LinkedHashSet::new));

                        boolean hasError = traceLogs.stream()
                                .anyMatch(l -> "ERROR".equalsIgnoreCase(l.getLevel()));

                        Map<String, Object> summary = new LinkedHashMap<>();
                        summary.put("traceId", traceId);
                        summary.put("serviceName", services.isEmpty() ? serviceName : String.join(" → ", services));
                        summary.put("serviceCount", services.size());
                        summary.put("logCount", traceLogs.size());
                        summary.put("duration", duration);
                        summary.put("timestamp", firstTime);
                        summary.put("formattedTime", formatTimestamp(firstTime));
                        summary.put("hasError", hasError);
                        return summary;
                    })
                    .sorted((a, b) -> Long.compare((long) b.get("timestamp"), (long) a.get("timestamp")))
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("搜索接口链路失败: apiPath={}, error={}", apiPath, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public Map<String, Object> queryTraceChain(String traceId, String timeRange, String timestamp) {
        // 检查数据库缓存
        String pageKey = "trace_chain";
        String dataKey = traceId + "_" + timeRange;
        Map<String, Object> dbCached = pageDataCacheService.get(pageKey, dataKey, Map.class);
        if (dbCached != null) {
            log.info("返回链路详情数据库缓存: traceId={}", traceId);
            return dbCached;
        }
        
        // 缓存未命中，查询并缓存
        Map<String, Object> result = loadTraceChain(traceId, timeRange, timestamp);
        if (result != null && !result.isEmpty()) {
            pageDataCacheService.save(pageKey, dataKey, result);
        }
        return result;
    }
    
    private Map<String, Object> loadTraceChain(String traceId, String timeRange, String timestamp) {
        long now = System.currentTimeMillis() / 1000;
        long from;
        long to;
        
        // If timestamp is provided, center the query window around it
        if (timestamp != null && !timestamp.isBlank()) {
            long traceTime = parseTraceTimestamp(timestamp);
            if (traceTime > 0) {
                // Query 30 minutes before and after the trace time
                from = traceTime - 1800;
                to = traceTime + 1800;
                log.info("使用指定时间戳查询链路: traceId={}, timestamp={}, from={}, to={}", 
                    traceId, timestamp, from, to);
            } else {
                // Fallback to default timeRange if timestamp parsing fails
                from = now - parseTimeRange(timeRange);
                to = now;
            }
        } else {
            // Default behavior: query from now-timeRange to now
            from = now - parseTimeRange(timeRange);
            to = now;
        }

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
            log.info("尝试查询链路 [{}/{}]: 策略={}, traceId={}, query={}, from={}, to={}", 
                i + 1, queries.length, queryLabels[i], traceId, query, from, to);
            try {
                logs = queryLogs(logstore, query, from, to, 0, 1000);
                log.info("查询结果: 策略={}, 日志数量={}", queryLabels[i], logs.size());
                if (!logs.isEmpty()) {
                    log.info("查询成功: 策略={}, traceId={}, 日志数量={}", queryLabels[i], traceId, logs.size());
                    break;
                }
            } catch (Exception e) {
                log.warn("查询失败: 策略={}, error={}", queryLabels[i], e.getMessage());
            }
        }
        
        // Fallback: query logs from specific services and filter by traceId
        if (logs.isEmpty()) {
            log.info("所有精确查询失败，尝试备用方案：从各服务查询日志并按traceId过滤");
            try {
                // Try querying from common services first (more efficient than querying all logs)
                String[] services = fallbackServices;
                
                for (String service : services) {
                    String serviceQuery = "__tag__:_container_name_: " + service;
                    List<LogEntry> serviceLogs = queryLogs(logstore, serviceQuery, from, to, 0, 1000);
                    log.info("从服务 {} 查询到 {} 条日志", service, serviceLogs.size());
                    
                    List<LogEntry> matched = serviceLogs.stream()
                        .filter(l -> traceId.equals(getTraceId(l)))
                        .collect(Collectors.toList());
                    
                    if (!matched.isEmpty()) {
                        log.info("在服务 {} 中找到 {} 条匹配的日志", service, matched.size());
                        logs = matched;
                        break;
                    }
                }
                
                // If still not found, try querying all logs with pagination
                if (logs.isEmpty()) {
                    log.info("各服务查询未找到，尝试分页查询所有日志");
                    List<LogEntry> allLogs = new ArrayList<>();
                    int pageSize = 100;
                    int maxPages = 50;
                    
                    for (int page = 0; page < maxPages; page++) {
                        int offset = page * pageSize;
                        List<LogEntry> pageLogs = queryLogs(logstore, "*", from, to, offset, pageSize);
                        if (pageLogs.isEmpty()) {
                            break;
                        }
                        allLogs.addAll(pageLogs);
                        
                        boolean found = pageLogs.stream().anyMatch(l -> traceId.equals(getTraceId(l)));
                        if (found && allLogs.size() >= 500) {
                            break;
                        }
                        
                        if (pageLogs.size() < pageSize) {
                            break;
                        }
                    }
                    
                    log.info("分页查询到 {} 条日志，开始按traceId过滤", allLogs.size());
                    
                    if (!allLogs.isEmpty()) {
                        List<String> sampleTraces = allLogs.stream()
                            .limit(10)
                            .map(l -> l.getTrace() == null ? "null" : l.getTrace())
                            .collect(Collectors.toList());
                        log.info("样本日志的trace值: {}", sampleTraces);
                        log.info("目标traceId: {}", traceId);
                    }
                    
                    logs = allLogs.stream()
                        .filter(l -> traceId.equals(getTraceId(l)))
                        .collect(Collectors.toList());
                }
                
                log.info("过滤后剩余 {} 条日志", logs.size());
            } catch (Exception e) {
                log.warn("备用方案失败: {}", e.getMessage());
            }
        }

        log.info("链路查询最终结果: traceId={}, 日志数量={}", traceId, logs.size());

        if (logs.isEmpty()) {
            log.info("SLS 无链路日志，使用演示链路数据: traceId={}", traceId);
            return generateDemoTraceChain(traceId);
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

    /**
     * 演示链路数据：SLS 无日志时返回多服务调用链示例，保证页面无空数据
     */
    private Map<String, Object> generateDemoTraceChain(String traceId) {
        long now = System.currentTimeMillis();
        String[][] services = {
                {"gateway-prod", "POST /api/order/create"},
                {"order-prod", "POST /order/create"},
                {"charge-prod", "POST /charge/pay"},
                {"user-prod", "GET /user/profile"}
        };
        String[][] messages = {
                {"收到请求 POST /api/order/create，开始转发", "路由匹配成功，转发至下游服务", "请求处理完成，耗时统计已上报"},
                {"开始处理订单创建，校验参数", "调用库存服务扣减库存成功", "订单落库完成，发送 Kafka 消息"},
                {"支付渠道路由：支付宝", "支付下单成功，等待异步通知", "支付流水落库完成"},
                {"查询用户画像信息命中缓存", "用户状态校验通过"}
        };
        int[] durations = {120, 350, 280, 15};
        List<Map<String, Object>> nodes = new ArrayList<>();
        long t = now - 800;
        int totalLogs = 0;
        for (int i = 0; i < services.length; i++) {
            List<Map<String, Object>> logs = new ArrayList<>();
            for (int j = 0; j < messages[i].length; j++) {
                Map<String, Object> logMap = new LinkedHashMap<>();
                logMap.put("time", String.valueOf(t + j * 40));
                logMap.put("formattedTime", formatTimestamp(t + j * 40));
                logMap.put("level", "INFO");
                logMap.put("message", messages[i][j]);
                logMap.put("trace", traceId);
                logMap.put("containerName", services[i][0]);
                logMap.put("containerIp", "10.0." + (i + 1) + "." + (j + 10));
                logs.add(logMap);
            }
            totalLogs += logs.size();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("serviceName", services[i][0]);
            node.put("apiPath", services[i][1]);
            node.put("timestamp", t);
            node.put("formattedTime", formatTimestamp(t));
            node.put("duration", durations[i]);
            node.put("logCount", logs.size());
            node.put("status", "success");
            node.put("logs", logs);
            nodes.add(node);
            t += durations[i] + 50;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("nodes", nodes);
        result.put("totalLogs", totalLogs);
        result.put("demo", true);
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

    /**
     * 从日志中提取 traceId，检查多个可能的字段名
     */
    private String getTraceId(LogEntry entry) {
        if (entry == null) return null;
        // 优先使用 trace 字段
        if (entry.getTrace() != null && !entry.getTrace().isBlank()) {
            return entry.getTrace();
        }
        // 检查其他可能的 trace 字段
        if (entry.getFields() != null) {
            String[] traceFieldNames = {"traceId", "trace_id", "requestId", "request_id", "spanId", "span_id"};
            for (String fieldName : traceFieldNames) {
                String value = entry.getFields().get(fieldName);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
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

    private long parseTraceTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return 0;
        try {
            // Handle URL-encoded space (+ or %20)
            String normalized = timestamp.replace("+", " ").replace("%20", " ");
            // Try parsing "yyyy-MM-dd HH:mm:ss.SSS" format
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(normalized,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            return ldt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception e1) {
            try {
                // Try parsing "yyyy-MM-dd HH:mm:ss" format (without milliseconds)
                String normalized = timestamp.replace("+", " ").replace("%20", " ");
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(normalized,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return ldt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
            } catch (Exception e2) {
                log.warn("无法解析时间戳: {}, error: {}", timestamp, e2.getMessage());
                return 0;
            }
        }
    }

    private long parseTimestamp(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return 0;
        
        // Try parsing as Unix timestamp first
        try {
            long ts = Long.parseLong(timeStr);
            return ts < 10000000000L ? ts * 1000 : ts;
        } catch (NumberFormatException e) {
            // Not a numeric timestamp, try date-time format
        }
        
        // Try parsing as date-time string (yyyy-MM-dd HH:mm:ss.SSS or similar)
        try {
            // Remove trailing .000 if present (milliseconds with all zeros)
            String cleaned = timeStr.replaceAll("\\.0+$", "");
            
            // Try various date-time formats
            java.time.format.DateTimeFormatter[] formatters = {
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            };
            
            for (java.time.format.DateTimeFormatter formatter : formatters) {
                try {
                    java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(cleaned, formatter);
                    return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                } catch (Exception ignored) {
                    // Try next formatter
                }
            }
        } catch (Exception e) {
            log.debug("解析时间失败: {}", timeStr);
        }
        
        return 0;
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
