package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.util.TimeRanges;
import com.ykc.hubble.vo.ErrorTrendVO;
import com.ykc.hubble.vo.ErrorTypeVO;
import com.ykc.hubble.vo.SlsKeywordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 错误分析服务：从 SLS 拉取 ERROR 日志，按异常特征归类出 Top N 与趋势。
 * <p>
 * P0 为内存近似聚合；P1 可演进为按时间桶分窗统计与精确环比。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorAnalysisService {

    private final AlertConfigService alertConfigService;
    private final SlsKeywordService slsKeywordService;
    private final SlsQueryClient slsQueryClient;
    private final MonitorProperties monitorProperties;

    /**
     * 从日志 message 提取 Java 异常类名（如 NullPointerException）
     */
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([A-Z][A-Za-z0-9_]*Exception)");

    /**
     * 错误类型排行 Top N
     */
    public List<ErrorTypeVO> topErrorTypes(Long configId, String timeRange, int limit) {
        List<LogEntry> logs = fetchErrorLogs(configId, timeRange);
        if (logs.isEmpty()) {
            return List.of();
        }

        // 按异常类名（或日志首段）归类
        Map<String, List<LogEntry>> grouped = new LinkedHashMap<>();
        for (LogEntry log : logs) {
            String typeName = extractTypeName(log.getMessage());
            grouped.computeIfAbsent(typeName, k -> new ArrayList<>()).add(log);
        }

        long total = logs.size();
        int top = limit <= 0 ? 10 : limit;
        return grouped.entrySet().stream()
                .map(e -> toVO(e.getKey(), e.getValue(), total))
                .sorted(Comparator.comparingLong(ErrorTypeVO::getCount).reversed())
                .limit(top)
                .collect(Collectors.toList());
    }

    /**
     * Top 错误类型趋势（P0 单点近似：返回当前各类型计数）
     */
    public ErrorTrendVO topErrorTypesTrend(Long configId, String timeRange, String interval) {
        List<ErrorTypeVO> types = topErrorTypes(configId, timeRange, 8);
        ErrorTrendVO vo = new ErrorTrendVO();
        vo.setTimestamps(List.of(System.currentTimeMillis()));
        List<ErrorTrendVO.Series> series = new ArrayList<>();
        for (ErrorTypeVO t : types) {
            ErrorTrendVO.Series s = new ErrorTrendVO.Series();
            s.setTypeName(t.getTypeName());
            s.setCounts(List.of(t.getCount()));
            series.add(s);
        }
        vo.setSeries(series);
        return vo;
    }

    /**
     * 错误数趋势（P0 返回空结构，前端兼容；P1 接入分桶统计）
     */
    public Map<String, Object> errorTrend(Long configId, String timeRange) {
        Map<String, Object> result = new HashMap<>();
        result.put("timestamps", List.of());
        result.put("counts", List.of());
        return result;
    }

    /**
     * 错误分类枚举
     */
    private enum ErrorCategory {
        NPE("空指针"),
        TIMEOUT("超时"),
        ERROR("其他错误");

        private final String displayName;

        ErrorCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 根据错误消息分类错误类型
     */
    private ErrorCategory classifyError(String message) {
        if (message == null || message.isBlank()) {
            return ErrorCategory.ERROR;
        }

        String lowerMessage = message.toLowerCase();

        // NPE 检测
        if (lowerMessage.contains("nullpointerexception") ||
            lowerMessage.contains("null pointer") ||
            lowerMessage.contains("cannot invoke method on null")) {
            return ErrorCategory.NPE;
        }

        // 超时检测
        if (lowerMessage.contains("timeout") ||
            lowerMessage.contains("timeoutexception") ||
            lowerMessage.contains("sockettimeout") ||
            lowerMessage.contains("connection timed out") ||
            lowerMessage.contains("read timed out")) {
            return ErrorCategory.TIMEOUT;
        }

        return ErrorCategory.ERROR;
    }

    /**
     * 直接查询 SLS ERROR 日志，按分钟分组，每分钟返回 top 5 错误类型。
     */
    public List<ErrorTypeVO> queryDirectErrors(String timeRange, int limit) {
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        String logstore = monitorProperties.getDefaultQueryLogstore();

        // 1. SQL 聚合获取每分钟的错误总数（最多15分钟）
        String minuteQuery = "level: ERROR | SELECT " +
                "date_format(from_unixtime(__time__), '%Y-%m-%d %H:%i') as minute, " +
                "count(*) as cnt " +
                "GROUP BY minute " +
                "ORDER BY minute DESC " +
                "LIMIT 15";

        List<Map<String, String>> minuteRows;
        try {
            minuteRows = slsQueryClient.queryAnalytics(logstore, minuteQuery, from, now, 15);
        } catch (Exception e) {
            log.error("聚合查询 ERROR 日志失败：{}", e.getMessage(), e);
            return List.of();
        }

        if (minuteRows.isEmpty()) {
            return List.of();
        }

        // 2. 对每个分钟，查询原始日志并按错误类型分组，取 top 5
        List<ErrorTypeVO> result = new ArrayList<>();
        for (Map<String, String> row : minuteRows) {
            String minute = row.getOrDefault("minute", "");
            long minuteTotal = 0;
            try {
                minuteTotal = Long.parseLong(row.getOrDefault("cnt", "0"));
            } catch (NumberFormatException e) {
                // ignore
            }

            // 查询该分钟的原始日志（最多100条）
            long minuteStart = parseTimeToMillis(minute) / 1000;
            long minuteEnd = minuteStart + 60;
            List<LogEntry> minuteLogs;
            try {
                minuteLogs = slsQueryClient.queryLogstore(logstore, "level: ERROR", minuteStart, minuteEnd, 0, 100);
            } catch (Exception e) {
                log.debug("查询分钟 {} 的日志失败: {}", minute, e.getMessage());
                continue;
            }

            // 按错误类型分组
            Map<ErrorCategory, List<LogEntry>> byType = new LinkedHashMap<>();
            for (LogEntry entry : minuteLogs) {
                ErrorCategory category = classifyError(entry.getMessage());
                byType.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
            }

            // 取 top 5 错误类型（按数量排序）
            List<Map.Entry<ErrorCategory, List<LogEntry>>> sortedTypes = byType.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                    .limit(5)
                    .toList();

            for (Map.Entry<ErrorCategory, List<LogEntry>> typeEntry : sortedTypes) {
                ErrorCategory errorType = typeEntry.getKey();
                List<LogEntry> typeLogs = typeEntry.getValue();

                ErrorTypeVO vo = new ErrorTypeVO();
                vo.setTypeName(errorType.getDisplayName());
                vo.setCount(typeLogs.size());
                vo.setPercentage(minuteTotal == 0 ? 0 : typeLogs.size() * 100.0 / minuteTotal);
                vo.setGrowthRate(0);
                vo.setCategory(errorType.name());
                vo.setFirstSeenAt(parseTimeToMillis(minute));
                String sampleMsg = typeLogs.get(0).getMessage();
                vo.setSampleLog(sampleMsg != null && sampleMsg.length() > 500 ? sampleMsg.substring(0, 500) : sampleMsg);
                result.add(vo);
            }
        }

        return result;
    }

    /**
     * 将时间字符串转换为分钟级别的key（格式：yyyy-MM-dd HH:mm）
     */
    private String getMinuteKey(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return String.valueOf(System.currentTimeMillis());
        }
        
        // 尝试解析为时间戳
        try {
            long timestamp = Long.parseLong(timeStr);
            // 如果是秒级时间戳，转换为毫秒
            if (timestamp < 10000000000L) {
                timestamp = timestamp * 1000;
            }
            java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.of("Asia/Shanghai"));
            return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (NumberFormatException e) {
            // 如果不是数字，尝试解析为格式化时间
            for (String pattern : new String[]{
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    "yyyy-MM-dd HH:mm:ss"}) {
                try {
                    java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(timeStr,
                            java.time.format.DateTimeFormatter.ofPattern(pattern));
                    return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                } catch (Exception ignored) {
                }
            }
            return String.valueOf(System.currentTimeMillis());
        }
    }

    /**
     * 拉取错误日志：模板 keywords + level=ERROR 过滤
     */
    private List<LogEntry> fetchErrorLogs(Long configId, String timeRange) {
        try {
            AlertConfig cfg = alertConfigService.detail(configId);
            if (cfg == null || cfg.getKeywordTemplateId() == null) {
                return List.of();
            }
            SlsKeywordVO template = slsKeywordService.getSlsKeywordDetail(cfg.getKeywordTemplateId());
            if (template == null) {
                return List.of();
            }
            String logstore = (template.getLogstore() != null && !template.getLogstore().isBlank())
                    ? template.getLogstore()
                    : monitorProperties.getDefaultQueryLogstore();
            long now = System.currentTimeMillis() / 1000;
            long from = now - TimeRanges.toSeconds(timeRange);
            String query = buildErrorQuery(template.getKeywords());
            return slsQueryClient.queryLogstore(logstore, query, from, now, 0, monitorProperties.getErrorScanLines());
        } catch (Exception e) {
            log.error("拉取错误日志失败 configId={}: {}", configId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 拼接 SLS 查询语句：模板关键字 AND level:ERROR
     */
    private String buildErrorQuery(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return "level: ERROR";
        }
        return keywords + " and level: ERROR";
    }

    private ErrorTypeVO toVO(String typeName, List<LogEntry> items, long total) {
        ErrorTypeVO vo = new ErrorTypeVO();
        vo.setTypeName(typeName);
        vo.setCount(items.size());
        vo.setPercentage(total == 0 ? 0 : items.size() * 100.0 / total);
        vo.setGrowthRate(0);
        vo.setCategory(guessCategory(typeName));
        vo.setFirstSeenAt(parseTimeToMillis(items.get(0).getTime()));
        vo.setSampleLog(items.get(0).getMessage());
        return vo;
    }

    /**
     * 构建服务维度的错误统计 VO，typeName 字段存放服务名
     */
    private ErrorTypeVO toServiceVO(String serviceName, List<LogEntry> items, long total) {
        ErrorTypeVO vo = new ErrorTypeVO();
        vo.setTypeName(serviceName);
        vo.setCount(items.size());
        vo.setPercentage(total == 0 ? 0 : items.size() * 100.0 / total);
        vo.setGrowthRate(0);
        vo.setCategory("服务");
        Long firstSeen = parseTimeToMillis(items.get(0).getTime());
        vo.setFirstSeenAt(firstSeen != null ? firstSeen : System.currentTimeMillis());
        vo.setSampleLog(items.get(0).getMessage());
        return vo;
    }

    /**
     * 提取错误类型名：优先异常类名，否则取日志首段
     */
    private String extractTypeName(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown";
        }
        Matcher m = EXCEPTION_PATTERN.matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        return message.length() > 40 ? message.substring(0, 40) + "..." : message;
    }

    /**
     * 粗略分类，便于前端分组展示
     */
    private String guessCategory(String typeName) {
        if (typeName == null) {
            return "其他";
        }
        if (typeName.contains("Null")) {
            return "空指针";
        }
        if (typeName.contains("Timeout")) {
            return "超时";
        }
        if (typeName.contains("IO") || typeName.contains("Sql") || typeName.contains("Connect")) {
            return "资源";
        }
        return "其他";
    }

    private Long parseTimeToMillis(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        // 尝试纯数字（秒级或毫秒级时间戳）
        try {
            long val = Long.parseLong(time);
            return val < 10000000000L ? val * 1000L : val;
        } catch (NumberFormatException ignored) {
        }
        // 尝试带小数点的时间戳（秒.毫秒）
        try {
            double val = Double.parseDouble(time);
            return (long) (val * 1000);
        } catch (NumberFormatException ignored) {
        }
        // 尝试格式化时间字符串
        for (String pattern : new String[]{
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss"}) {
            try {
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(time,
                        java.time.format.DateTimeFormatter.ofPattern(pattern));
                return ldt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
