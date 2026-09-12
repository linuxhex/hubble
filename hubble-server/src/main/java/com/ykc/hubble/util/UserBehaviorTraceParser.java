package com.ykc.hubble.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykc.hubble.vo.UserBehaviorTraceItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 用户行为轨迹消息解析工具类
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
public class UserBehaviorTraceParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 解析message字段，提取用户行为轨迹信息
     *
     * @param message 日志消息
     * @param logTime 日志时间（Unix时间戳，秒）
     * @return 用户行为轨迹项，解析失败返回null
     */
    public UserBehaviorTraceItemVO parse(String message, String logTime) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        try {
            String jsonStr = null;

            // 查找 "request:" 位置
            int requestIdx = message.indexOf("request:");
            if (requestIdx != -1) {
                // 提取request后的内容
                String requestPayload = message.substring(requestIdx + "request:".length()).trim();
                int braceIdx = requestPayload.indexOf("{");
                if (braceIdx != -1) {
                    jsonStr = requestPayload.substring(braceIdx);
                }
            }

            // 如果没有 "request:" 标记，尝试查找消息中任意 JSON 对象
            if (jsonStr == null) {
                int braceIdx = message.indexOf("{");
                if (braceIdx != -1) {
                    jsonStr = message.substring(braceIdx);
                }
            }

            if (jsonStr == null) {
                return null;
            }

            // 解析JSON
            JsonNode data = objectMapper.readTree(jsonStr);
            if (data == null) {
                return null;
            }

            // 创建VO对象
            UserBehaviorTraceItemVO item = new UserBehaviorTraceItemVO();

            // 从message中提取第三个方括号里的值作为追踪ID
            String extractedTrace = extractTraceFromMessage(message);
            item.setTrace(extractedTrace != null ? extractedTrace : "");

            // 转换为格式化时间字符串（秒转毫秒）
            item.setFormattedLogTime(logTime);

            // 提取基本字段
            item.setPageName(getStringValue(data, "pageName"));
            item.setPageid(getStringValue(data, "pageid"));
            item.setTerminal(getStringValue(data, "terminal"));
            item.setType(getStringValue(data, "type"));
            item.setUserAccount(getStringValue(data, "userAccount"));
            item.setUserId(getStringValue(data, "userId"));
            item.setFromPage(getStringValue(data, "fromPage"));
            item.setAppVersion(getStringValue(data, "appVersion"));
            item.setAbValue(getStringValue(data, "abValue"));

            // 解析dateTime（毫秒时间戳）
            String dateTimeStr = getStringValue(data, "dateTime");
            if (dateTimeStr != null && !dateTimeStr.isEmpty()) {
                try {
                    long dateTime = Long.parseLong(dateTimeStr);
                    item.setDateTime(dateTime);
                    item.setFormattedDateTime(formatTimestamp(dateTime));
                } catch (NumberFormatException e) {
                    log.warn("dateTime格式错误: {}", dateTimeStr);
                    // dateTime 解析失败时使用日志时间作为回退
                    item.setDateTime(parseLogTime(logTime));
                    item.setFormattedDateTime(logTime);
                }
            } else {
                // 没有 dateTime 字段时使用日志时间作为回退
                item.setDateTime(parseLogTime(logTime));
                item.setFormattedDateTime(logTime);
            }

            // 解析uploadData中的url、response
            String uploadDataStr = getStringValue(data, "uploadData");
            if (uploadDataStr != null && !uploadDataStr.isEmpty()) {
                try {
                    JsonNode uploadData = objectMapper.readTree(uploadDataStr);
                    if (uploadData != null) {
                        // 解析url
                        if (uploadData.has("url")) {
                            item.setUrl(getStringValue(uploadData, "url"));
                        } else {
                            item.setUrl("");
                        }

                        // 解析response（响应码和响应数据）
                        if (uploadData.has("response")) {
                            JsonNode response = uploadData.get("response");
                            
                            // 获取响应码（优先使用statusCode，其次使用status）
                            String statusCode = getStringValue(response, "statusCode");
                            if (statusCode.isEmpty()) {
                                statusCode = getStringValue(response, "status");
                            }
                            item.setResponseStatus(statusCode);

                            // 获取响应数据（response.data）
                            if (response.has("data")) {
                                JsonNode responseData = response.get("data");
                                try {
                                    // 将response.data转换为JSON字符串
                                    item.setResponseData(objectMapper.writeValueAsString(responseData));
                                } catch (Exception e) {
                                    log.debug("responseData序列化失败", e);
                                    item.setResponseData("");
                                }
                            } else {
                                item.setResponseData("");
                            }
                        } else {
                            item.setResponseStatus("");
                            item.setResponseData("");
                        }
                    }
                } catch (Exception e) {
                    log.debug("uploadData解析失败: {}", uploadDataStr, e);
                    item.setUrl("");
                    item.setResponseStatus("");
                    item.setResponseData("");
                }
            } else {
                item.setUrl("");
                item.setResponseStatus("");
                item.setResponseData("");
            }

            return item;

        } catch (Exception e) {
            log.warn("解析用户行为轨迹消息失败: {}", message, e);
            return null;
        }
    }

    /**
     * 从message中提取第三个方括号里的值作为trace
     * message格式示例: [NONE] [0] [50011158038876971241472] C-Tracking batchId: 7d
     *
     * @param message 日志消息
     * @return trace值，如果解析失败返回null
     */
    private String extractTraceFromMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        try {
            int bracketCount = 0;
            int startIdx = -1;
            int endIdx = -1;

            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                if (c == '[') {
                    bracketCount++;
                    if (bracketCount == 3) {
                        // 找到第三个方括号的开始位置
                        startIdx = i + 1;
                    }
                } else if (c == ']') {
                    if (bracketCount == 3 && startIdx != -1) {
                        // 找到第三个方括号的结束位置
                        endIdx = i;
                        break;
                    }
                }
            }

            if (startIdx != -1 && endIdx != -1 && startIdx < endIdx) {
                return message.substring(startIdx, endIdx).trim();
            }
        } catch (Exception e) {
            log.debug("从message中提取trace失败: {}", message, e);
        }

        return null;
    }

    /**
     * 获取JSON节点的字符串值
     */
    private String getStringValue(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) {
            return "";
        }
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return "";
        }
        return fieldNode.asText("");
    }

    /**
     * 将毫秒时间戳转换为格式化时间字符串
     */
    private String formatTimestamp(long timestampMs) {
        try {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(timestampMs),
                    DEFAULT_ZONE
            );
            return dateTime.format(DATE_TIME_FORMATTER);
        } catch (Exception e) {
            log.warn("时间戳格式化失败: {}", timestampMs, e);
            return "";
        }
    }

    /**
     * 将日志时间（秒级时间戳字符串）转换为毫秒级时间戳
     */
    private long parseLogTime(String logTime) {
        if (logTime == null || logTime.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            long seconds = Long.parseLong(logTime);
            return seconds * 1000;
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }
}

