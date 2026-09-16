package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.dto.GatewayLogQueryDTO;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.vo.GatewayLogVO;
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
public class GatewayLogService {

    private final SlsQueryClient slsQueryClient;
    private final MonitorProperties monitorProperties;
    private final SlsConfig slsConfig;

    public Map<String, Object> queryLogs(GatewayLogQueryDTO dto) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - 86400;

        // 支持 KeywordLogQuery 页面的 timeRange 字段
        if (dto.getTimeRange() != null) {
            Long rangeFrom = dto.getTimeRange().get("from");
            Long rangeTo = dto.getTimeRange().get("to");
            if (rangeFrom != null) from = rangeFrom;
            if (rangeTo != null) now = rangeTo;
        }

        // 支持 logstore 覆盖
        String logstore = (dto.getLogstore() != null && !dto.getLogstore().isBlank())
                ? dto.getLogstore()
                : monitorProperties.getDefaultQueryLogstore();

        // keyword 作为原始 SLS 查询语句；否则按字段拼接
        String query = (dto.getKeyword() != null && !dto.getKeyword().isBlank())
                ? dto.getKeyword().trim()
                : buildQuery(dto);

        int queryOffset = dto.getOffset() != null ? dto.getOffset() : 0;
        int queryLimit = dto.getLimit() != null ? dto.getLimit() : 200;

        List<LogEntry> logs = queryLogs(logstore, query, from, now, queryOffset, queryLimit);

        // 如果使用了 offset/limit 分页（KeywordLogQuery 模式），返回原始 LogEntry 数据
        if (dto.getOffset() != null) {
            // 排序方式
            if ("asc".equalsIgnoreCase(dto.getSortOrder())) {
                Collections.reverse(logs);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", logs.size());
            result.put("logs", logs);
            result.put("hasMore", logs.size() >= queryLimit);
            return result;
        }

        // 否则使用 page/pageSize 分页（GatewayLogs 模式），转换为 VO
        List<GatewayLogVO> voList = logs.stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        // 排序方式
        if ("asc".equalsIgnoreCase(dto.getSortOrder())) {
            Collections.reverse(voList);
        }

        int total = voList.size();
        int page = dto.getPage() != null ? dto.getPage() : 1;
        int pageSize = dto.getPageSize() != null ? dto.getPageSize() : 20;
        int fromIndex = Math.min((page - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<GatewayLogVO> paged = voList.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("records", paged);
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

    private String buildQuery(GatewayLogQueryDTO dto) {
        List<String> conditions = new ArrayList<>();
        if (notBlank(dto.getAppName())) conditions.add("__tag__:_container_name_: " + dto.getAppName());
        if (notBlank(dto.getUrl())) conditions.add("message: " + dto.getUrl());
        if (notBlank(dto.getStatusCode())) conditions.add("status: " + dto.getStatusCode());
        if (notBlank(dto.getUserId())) conditions.add("userId: " + dto.getUserId());
        if (notBlank(dto.getTraceId())) {
            // 判断是标准 traceId（含字母）还是纯数字业务ID
            String traceId = dto.getTraceId().trim();
            if (traceId.matches("^[0-9]+$")) {
                // 纯数字ID，同时搜索 trace 和 message 字段
                conditions.add("(trace: " + traceId + " or message: " + traceId + ")");
            } else {
                // 标准 traceId（含十六进制等），只搜索 trace 字段
                conditions.add("trace: " + traceId);
            }
        }
        if (notBlank(dto.getPhone())) conditions.add("phone: " + dto.getPhone());
        return conditions.isEmpty() ? "*" : String.join(" and ", conditions);
    }

    private GatewayLogVO toVO(LogEntry entry) {
        GatewayLogVO vo = new GatewayLogVO();
        vo.setAppName(entry.getContainerName() != null ? entry.getContainerName() : "");
        vo.setServerIp(entry.getContainerIp() != null ? entry.getContainerIp() : "");
        vo.setTraceId(entry.getTrace() != null ? entry.getTrace() : "");

        String message = entry.getMessage() != null ? entry.getMessage() : "";
        vo.setMessage(message);

        Map<String, String> fields = entry.getFields();

        // 优先从 SLS 结构化字段取 URL 和 userId
        if (fields != null) {
            String fieldUrl = fields.getOrDefault("url", fields.getOrDefault("requestUrl",
                    fields.getOrDefault("path", fields.getOrDefault("request_uri", null))));
            if (fieldUrl != null && !fieldUrl.isBlank()) {
                vo.setUrl(fieldUrl);
            }
            String fieldUserId = fields.getOrDefault("userId", fields.getOrDefault("user_id",
                    fields.getOrDefault("operatorId", fields.getOrDefault("uid", null))));
            if (fieldUserId != null && !fieldUserId.isBlank()) {
                vo.setUserId(fieldUserId);
            }
            String fieldStatus = fields.getOrDefault("status", fields.getOrDefault("statusCode",
                    fields.getOrDefault("responseStatus", null)));
            if (fieldStatus != null) {
                try {
                    vo.setStatusCode(Integer.parseInt(fieldStatus.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }

        // 从 message 中解析缺失的 URL、userId、耗时等信息
        parseMessageFields(message, vo);

        // 提取下游依赖服务名
        vo.setDownstreamService(extractDownstreamService(message));

        if (entry.getTime() != null) {
            try {
                long ts = Long.parseLong(entry.getTime());
                vo.setTimestamp(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(ts < 10000000000L ? ts * 1000 : ts)));
            } catch (NumberFormatException e) {
                // time 可能是格式化字符串如 "2026-08-02 10:55:38.043"，直接使用
                vo.setTimestamp(entry.getTime());
            }
        }

        // 如果 statusCode 还没被设置（仍为默认值 0），根据日志级别推断
        if (vo.getStatusCode() == 0) {
            String level = entry.getLevel() != null ? entry.getLevel().toUpperCase() : "INFO";
            vo.setStatusCode("ERROR".equals(level) ? 500 : "WARN".equals(level) ? 400 : 200);
        }
        return vo;
    }

    /**
     * 从日志 message 中解析 URL、userId、耗时等字段
     */
    private void parseMessageFields(String message, GatewayLogVO vo) {
        if (message == null || message.isBlank()) {
            if (vo.getUrl() == null || vo.getUrl().isBlank()) vo.setUrl("");
            if (vo.getUserId() == null || vo.getUserId().isBlank()) vo.setUserId("");
            vo.setDuration(0);
            return;
        }

        // 解析 URL（仅在尚未设置时）
        if (vo.getUrl() == null || vo.getUrl().isBlank()) {
            String url = extractUrl(message);
            vo.setUrl(url != null ? url : "");
        }

        // 解析 userId（仅在尚未设置时）
        if (vo.getUserId() == null || vo.getUserId().isBlank()) {
            String userId = extractUserId(message);
            vo.setUserId(userId != null ? userId : "");
        }

        // 解析耗时
        java.util.regex.Matcher durationMatcher = java.util.regex.Pattern
                .compile("(?:cost|useTime|took|elapsed|rt)[:\\s=]+(\\d+)\\s*(?:ms)?").matcher(message);
        if (durationMatcher.find()) {
            vo.setDuration(Double.parseDouble(durationMatcher.group(1)));
        } else {
            java.util.regex.Matcher msMatcher = java.util.regex.Pattern
                    .compile("(\\d+)\\s*ms").matcher(message);
            if (msMatcher.find()) {
                vo.setDuration(Double.parseDouble(msMatcher.group(1)));
            } else {
                vo.setDuration(0);
            }
        }
    }

    private String extractUrl(String message) {
        // requestUrl:/path 或 url=/path 或 path:/path
        java.util.regex.Matcher kvMatcher = java.util.regex.Pattern
                .compile("(?:requestUrl|url|path|request_uri|apiUrl)[:=]\\s*(/\\S+?)(?:[;,\\s\"']|$)").matcher(message);
        if (kvMatcher.find()) return kvMatcher.group(1);

        // JSON "url":"/path" 或 "path":"/path"
        java.util.regex.Matcher jsonMatcher = java.util.regex.Pattern
                .compile("\"(?:url|path|requestUrl|apiUrl)\"\\s*:\\s*\"([^\"]+)\"").matcher(message);
        if (jsonMatcher.find()) return jsonMatcher.group(1);

        // HTTP 方法 + 路径（大小写不敏感）
        java.util.regex.Matcher httpMatcher = java.util.regex.Pattern
                .compile("(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(/\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (httpMatcher.find()) return httpMatcher.group(1);

        // Feign 调用：POST http://host/path → 提取路径
        java.util.regex.Matcher feignMatcher = java.util.regex.Pattern
                .compile("(?:GET|POST|PUT|DELETE|PATCH)\\s+https?://[^/]+(/\\S+?)(?:[,\\s\"']|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (feignMatcher.find()) return feignMatcher.group(1);

        return null;
    }

    private String extractUserId(String message) {
        // JSON "userId":"xxx" 或 "userId":123（数字不加引号也匹配）
        java.util.regex.Matcher jsonMatcher = java.util.regex.Pattern
                .compile("\"(?:userId|operatorId|uid|user_id|loginId|customerId)\"\\s*:\\s*\"?(\\d+)\"?").matcher(message);
        if (jsonMatcher.find()) return jsonMatcher.group(1);

        // key=value 或 key:value 格式
        java.util.regex.Matcher kvMatcher = java.util.regex.Pattern
                .compile("(?:userId|operatorId|uid|user_id|loginId)[=:]\\s*(\\d+)").matcher(message);
        if (kvMatcher.find()) return kvMatcher.group(1);

        // 中文 "用户:123" 或 "用户ID:123"
        java.util.regex.Matcher cnMatcher = java.util.regex.Pattern
                .compile("用户(?:ID)?[：:]\\s*(\\d+)").matcher(message);
        if (cnMatcher.find()) return cnMatcher.group(1);

        return null;
    }

    private String extractDownstreamService(String message) {
        if (message == null || message.isBlank()) return null;

        // [SERVICE-NAME] 格式：日志开头的方括号服务名，如 [OMP-POLY-CENTER]
        java.util.regex.Matcher bracketMatcher = java.util.regex.Pattern
                .compile("^\\[([A-Z][A-Z0-9_-]+)\\]")
                .matcher(message);
        if (bracketMatcher.find()) {
            String name = bracketMatcher.group(1);
            if (!"NONE".equals(name) && !"NULL".equals(name) && !"UNKNOWN".equals(name)) {
                return name;
            }
        }

        // Nacos Service 对象：name='service-name' 或 name="service-name"
        java.util.regex.Matcher nacosMatcher = java.util.regex.Pattern
                .compile("name=['\"]([a-zA-Z][\\w-]*)['\"]")
                .matcher(message);
        if (nacosMatcher.find()) return nacosMatcher.group(1);

        // Feign/RestTemplate HTTP 调用: POST http://service-name/path
        java.util.regex.Matcher httpCallMatcher = java.util.regex.Pattern
                .compile("(?:POST|GET|PUT|DELETE|PATCH)\\s+https?://([a-zA-Z][\\w-]*)/", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (httpCallMatcher.find()) return httpCallMatcher.group(1);

        // Feign 客户端: XxxService#method 或 XxxClient#method
        java.util.regex.Matcher feignMatcher = java.util.regex.Pattern
                .compile("(\\w+(?:Service|Client|FeignClient))#(\\w+)")
                .matcher(message);
        if (feignMatcher.find()) return feignMatcher.group(1);

        // RestTemplate 调用: restTemplate.xxx("http://service-name/...")
        java.util.regex.Matcher restMatcher = java.util.regex.Pattern
                .compile("restTemplate\\.\\w+\\([\"']https?://([a-zA-Z][\\w-]*)/", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (restMatcher.find()) return restMatcher.group(1);

        // Calling/Invoking 下游服务
        java.util.regex.Matcher callMatcher = java.util.regex.Pattern
                .compile("(?:Calling|Invoking|FeignClient)\\s+([a-zA-Z][\\w-]*(?:-server|-prod|-uat|-service))", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (callMatcher.find()) return callMatcher.group(1);

        return null;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private boolean isSlsConfigured() {
        String accessKeyId = slsConfig.getAccessKeyId();
        String project = slsConfig.getProject();
        if (accessKeyId == null || accessKeyId.startsWith("your-") || accessKeyId.isBlank()) return false;
        if (project == null || project.startsWith("your-") || project.isBlank()) return false;
        return true;
    }
}
