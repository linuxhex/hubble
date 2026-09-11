package com.ykc.hubble.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykc.hubble.config.BizAnalysisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 数仓查询客户端：通过 MCP JSON-RPC 协议直连 query-server，执行只读 SQL。
 * 协议与 cwork-data 的 mcp_client.py 一致：POST {base_url}/mcp，JSON-RPC tools/call query_doris。
 */
@Slf4j
@Component
public class DorisQueryClient {

    private final BizAnalysisProperties properties;
    private final RestTemplate dorisRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {2000, 4000, 8000};

    public DorisQueryClient(BizAnalysisProperties properties,
                            @org.springframework.beans.factory.annotation.Qualifier("dorisRestTemplate") RestTemplate dorisRestTemplate) {
        this.properties = properties;
        this.dorisRestTemplate = dorisRestTemplate;
    }

    public List<Map<String, Object>> query(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }

        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("SHOW") && !upper.startsWith("DESC")) {
            log.warn("拒绝非只读SQL: {}", trimmed.substring(0, Math.min(50, trimmed.length())));
            return List.of();
        }

        String sqlPreview = trimmed.substring(0, Math.min(80, trimmed.length()));

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doQuery(trimmed);
            } catch (Exception e) {
                if (!isRetryable(e)) {
                    log.error("Doris 查询失败(不可重试), SQL: {}, 错误: {}", sqlPreview, e.getMessage());
                    return List.of();
                }
                if (attempt < MAX_RETRIES) {
                    long delay = BACKOFF_MS[attempt - 1];
                    log.warn("Doris 查询失败(第{}/{}次), {}ms后重试, SQL: {}, 错误: {}",
                            attempt, MAX_RETRIES, delay, sqlPreview, e.getMessage());
                    try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("Doris 查询失败(已重试{}次), SQL: {}, 错误: {}",
                            MAX_RETRIES, sqlPreview, e.getMessage());
                }
            }
        }
        return List.of();
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("timed out") || msg.contains("Connect timed out")
            || msg.contains("Connection reset") || msg.contains("Connection refused")
            || msg.contains("Server disconnected") || msg.contains("Unexpected end of file")
            || msg.contains("I/O error") || msg.contains("read timed out")
            || msg.contains("Doris 瞬时错误")
            || e instanceof org.springframework.web.client.ResourceAccessException;
    }

    private boolean isRetryableErrorMsg(String errorMsg) {
        if (errorMsg == null) return false;
        return errorMsg.contains("Server disconnected")
            || errorMsg.contains("Connection reset")
            || errorMsg.contains("timed out")
            || errorMsg.contains("Connection refused");
    }

    private List<Map<String, Object>> doQuery(String trimmed) {
        String url = properties.getQueryServerUrl() + "/mcp";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", 1);
        payload.put("method", "tools/call");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "query_doris");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("sql", trimmed);
        params.put("arguments", arguments);
        payload.put("params", params);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json, text/event-stream");
        if (properties.getQueryUser() != null && !properties.getQueryUser().isBlank()) {
            headers.set("X-User-Name", properties.getQueryUser());
        }
        if (properties.getQueryPass() != null && !properties.getQueryPass().isBlank()) {
            headers.set("X-Password", properties.getQueryPass());
        }

        org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(payload, headers);
        var response = dorisRestTemplate.postForObject(url, entity, String.class);
        if (response == null) {
            log.warn("Doris query-server 返回空");
            return List.of();
        }

        return parseMcpResponse(response, trimmed);
    }

    /**
     * 解析 MCP JSON-RPC 响应，提取 query_doris 结果行。
     * 响应结构：{result: {content: [{text: "{error_code:0, result:{data:[[...]], meta:[{name,...}]}}"]}}
     */
    private List<Map<String, Object>> parseMcpResponse(String response, String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String json = extractJsonFromSse(response);
            JsonNode root = objectMapper.readTree(json);

            JsonNode textNode = root.path("result").path("content").path(0).path("text");
            String innerJson;
            if (textNode.isTextual()) {
                innerJson = textNode.asText();
            } else {
                innerJson = json;
            }

            JsonNode inner = objectMapper.readTree(innerJson);

            int errorCode = inner.path("error_code").asInt(0);
            if (errorCode != 0) {
                String errorMsg = inner.path("error_msg").asText("unknown");
                if (isRetryableErrorMsg(errorMsg)) {
                    throw new RuntimeException("Doris 瞬时错误: " + errorCode + " - " + errorMsg);
                }
                log.warn("Doris 查询返回错误, SQL: {}, error_code: {}, error_msg: {}",
                    sql.substring(0, Math.min(100, sql.length())), errorCode, errorMsg);
                return List.of();
            }

            JsonNode resultNode = inner.path("result");
            JsonNode dataNode = resultNode.path("data");
            JsonNode metaNode = resultNode.path("meta");

            if (dataNode.isArray() && metaNode.isArray()) {
                // 提取列名
                List<String> colNames = new ArrayList<>();
                for (JsonNode meta : metaNode) {
                    colNames.add(meta.path("name").asText());
                }

                // 按列名映射每行
                for (JsonNode row : dataNode) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < colNames.size() && i < row.size(); i++) {
                        JsonNode val = row.get(i);
                        if (val == null || val.isNull()) {
                            map.put(colNames.get(i), null);
                        } else if (val.isNumber()) {
                            map.put(colNames.get(i), val.doubleValue());
                        } else if (val.isBoolean()) {
                            map.put(colNames.get(i), val.booleanValue());
                        } else {
                            map.put(colNames.get(i), val.asText());
                        }
                    }
                    result.add(map);
                }
            }
        } catch (Exception e) {
            log.error("解析 Doris MCP 响应失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * MCP 端点返回 SSE 格式（event: message\ndata: {json}），提取 data: 后的 JSON 部分。
     * 如果响应已经是纯 JSON（无 SSE 前缀），直接返回。
     */
    private String extractJsonFromSse(String response) {
        if (response == null) return "";
        String trimmed = response.trim();
        // 纯 JSON 直接返回
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // SSE 格式：找最后一个 "data: " 行
        int dataIdx = trimmed.lastIndexOf("data: ");
        if (dataIdx >= 0) {
            return trimmed.substring(dataIdx + 6).trim();
        }
        return trimmed;
    }
}
