package com.ykc.hubble.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykc.hubble.config.BizAnalysisProperties;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class DorisQueryClient {

    private final BizAnalysisProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        try {
            // MCP JSON-RPC 协议：POST /mcp，方法 tools/call，工具 query_doris
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

            // 认证走请求头（与 cwork-data mcp_client.py 一致）
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
            var response = restTemplate.postForObject(url, entity, String.class);
            if (response == null) {
                log.warn("Doris query-server 返回空");
                return List.of();
            }

            return parseMcpResponse(response);
        } catch (Exception e) {
            log.error("Doris 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 MCP JSON-RPC 响应，提取 query_doris 结果行。
     * 响应结构：{result: {content: [{text: "{error_code:0, result:{data:[[...]], meta:[{name,...}]}}"]}}
     */
    private List<Map<String, Object>> parseMcpResponse(String response) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // MCP 端点返回 SSE 格式（event: message\ndata: {json}），需提取 data: 后的 JSON
            String json = extractJsonFromSse(response);
            JsonNode root = objectMapper.readTree(json);

            // MCP 响应：result.content[0].text 是内层 JSON 字符串
            JsonNode textNode = root.path("result").path("content").path(0).path("text");
            String innerJson;
            if (textNode.isTextual()) {
                innerJson = textNode.asText();
            } else {
                // 兜底：可能直接是 data 结构
                innerJson = json;
            }

            JsonNode inner = objectMapper.readTree(innerJson);
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
