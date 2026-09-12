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
        return parse(message, logTime, null, null, null);
    }

    /**
     * 解析message字段，提取用户行为轨迹信息
     *
     * @param message       日志消息
     * @param logTime       日志时间（Unix时间戳，秒）
     * @param level         日志级别（来自SLS LogEntry）
     * @param containerName 容器名/服务名（来自SLS LogEntry）
     * @param containerIp   容器IP（来自SLS LogEntry）
     * @return 用户行为轨迹项，解析失败返回null
     */
    public UserBehaviorTraceItemVO parse(String message, String logTime,
                                          String level, String containerName, String containerIp) {
        if (message == null || message.isEmpty()) {
            return null;
        }

        try {
            String jsonStr = null;

            // 查找 "request:" 位置
            int requestIdx = message.indexOf("request:");
            if (requestIdx != -1) {
                String requestPayload = message.substring(requestIdx + "request:".length()).trim();
                int braceIdx = requestPayload.indexOf("{");
                if (braceIdx != -1) {
                    jsonStr = requestPayload.substring(braceIdx);
                }
            }

            // 查找 "message=" 位置（ZDL日志格式: [ZDL] ... message={...}）
            if (jsonStr == null) {
                int msgIdx = message.indexOf("message=");
                if (msgIdx != -1) {
                    String msgPayload = message.substring(msgIdx + "message=".length()).trim();
                    int braceIdx = msgPayload.indexOf("{");
                    if (braceIdx != -1) {
                        jsonStr = msgPayload.substring(braceIdx);
                    }
                }
            }

            // 如果没有特殊标记，尝试查找消息中任意 JSON 对象
            if (jsonStr == null) {
                int braceIdx = message.indexOf("{");
                if (braceIdx != -1) {
                    jsonStr = message.substring(braceIdx);
                }
            }

            UserBehaviorTraceItemVO item = new UserBehaviorTraceItemVO();
            item.setFormattedLogTime(formatLogTime(logTime));

            // 填充 SLS LogEntry 提供的字段
            if (level != null) item.setLogLevel(level);
            if (containerName != null) item.setServiceName(containerName);
            if (containerIp != null) item.setClientIp(containerIp);

            if (jsonStr == null) {
                // 无 JSON：按后端服务日志格式解析 [serviceName][span][traceId] message
                return parseBackendLog(item, message, logTime);
            }

            // 解析JSON
            JsonNode data = objectMapper.readTree(jsonStr);
            if (data == null) {
                return null;
            }

            // 从message中提取第三个方括号里的值作为追踪ID
            String extractedTrace = extractTraceFromMessage(message);
            item.setTrace(extractedTrace != null ? extractedTrace : "");

            // 提取基本字段
            item.setPageName(getStringValue(data, "pageName"));
            item.setPageid(getStringValue(data, "pageid"));
            item.setTerminal(getStringValue(data, "terminal"));
            item.setType(getStringValue(data, "type"));

            String userAccount = getStringValue(data, "userAccount");
            if (userAccount.isEmpty()) userAccount = getStringValue(data, "userPhone");
            item.setUserAccount(userAccount);

            String userId = getStringValue(data, "userId");
            if (userId.isEmpty()) userId = getStringValue(data, "uid");
            item.setUserId(userId);

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

            item.setLogMessage(extractLogMessage(message));

            // 提取 api 路径（ControllerLog 格式中的 apiUrl）
            String apiUrl = getStringValue(data, "apiUrl");
            if (!apiUrl.isEmpty()) {
                item.setApi(apiUrl);
            }

            return item;

        } catch (Exception e) {
            log.warn("解析用户行为轨迹消息失败: {}", message, e);
            return null;
        }
    }

    /**
     * 解析后端服务日志格式: [serviceName][span][traceId] message
     */
    private UserBehaviorTraceItemVO parseBackendLog(UserBehaviorTraceItemVO item, String message, String logTime) {
        // 提取第一个方括号内容作为 serviceName（如果 VO 中还没有）
        if (item.getServiceName() == null || item.getServiceName().isEmpty()) {
            String firstBracket = extractBracketContent(message, 1);
            if (firstBracket != null) {
                item.setServiceName(firstBracket);
            }
        }

        // 提取第三个方括号作为 trace
        String trace = extractTraceFromMessage(message);
        item.setTrace(trace != null ? trace : "");

        // 提取方括号后的文本作为 logMessage
        String logMsg = extractLogMessage(message);
        item.setLogMessage(logMsg != null ? logMsg : "");

        // 设置时间
        item.setDateTime(parseLogTime(logTime));
        item.setFormattedDateTime(formatLogTime(logTime));

        return item;
    }

    /**
     * 提取第 n 个方括号的内容
     */
    private String extractBracketContent(String message, int bracketIndex) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        try {
            int count = 0;
            int startIdx = -1;
            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                if (c == '[') {
                    count++;
                    if (count == bracketIndex) {
                        startIdx = i + 1;
                    }
                } else if (c == ']' && count == bracketIndex && startIdx != -1) {
                    return message.substring(startIdx, i).trim();
                }
            }
        } catch (Exception e) {
            log.debug("提取第{}个方括号内容失败: {}", bracketIndex, message, e);
        }
        return null;
    }

    /**
     * 提取日志消息文本用于展示。
     * 跳过开头所有连续的 [...] 对，取剩余文本；若有 message= 则只取 message= 前的描述部分。
     */
    private String extractLogMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        try {
            // 1. 跳过开头所有连续的 [...] 对
            int pos = 0;
            while (pos < message.length() && message.charAt(pos) == '[') {
                int close = message.indexOf(']', pos);
                if (close < 0) break;
                pos = close + 1;
                // 跳过括号后的空格
                while (pos < message.length() && message.charAt(pos) == ' ') pos++;
            }

            if (pos > 0 && pos < message.length()) {
                String remaining = message.substring(pos);
                // 如果有 message=，只取 message= 前的描述
                int msgEqIdx = remaining.indexOf("message=");
                if (msgEqIdx > 0) {
                    return remaining.substring(0, msgEqIdx).trim().replaceAll(",\\s*$", "");
                }
                return remaining.trim();
            }

            // 2. 无方括号开头：若有 message= 则取前面的描述文本
            int msgIdx = message.indexOf("message=");
            if (msgIdx > 0) {
                return message.substring(0, msgIdx).trim().replaceAll(",\\s*$", "");
            }

            // 3. 整个消息
            return message.trim();
        } catch (Exception e) {
            log.debug("提取日志消息失败: {}", message, e);
        }
        return message;
    }

    /**
     * 将日志时间（秒级时间戳字符串）转换为格式化时间字符串
     */
    private String formatLogTime(String logTime) {
        if (logTime == null || logTime.isEmpty()) {
            return "";
        }
        try {
            long seconds = Long.parseLong(logTime);
            return formatTimestamp(seconds * 1000);
        } catch (NumberFormatException e) {
            return logTime;
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

