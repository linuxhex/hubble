package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.MiddlewareAlertConfig;
import com.ykc.hubble.mapper.MiddlewareAlertConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MiddlewareAlertService {

    private final MiddlewareAlertConfigMapper alertConfigMapper;
    private final DingTalkClient dingTalkClient;
    private final AlertPushService alertPushService;
    private final MonitorProperties monitorProperties;

    private List<MiddlewareAlertConfig> configCache = null;
    private long configCacheTime = 0;
    private static final long CONFIG_CACHE_TTL_MS = 60 * 1000;

    // 中间件告警防抖：同一实例同一指标 24 小时内不重复告警
    private final Map<String, Long> alertLastSent = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 24 * 60 * 60 * 1000L;

    public List<MiddlewareAlertConfig> listByType(String middlewareType) {
        return alertConfigMapper.selectList(
                new LambdaQueryWrapper<MiddlewareAlertConfig>()
                        .eq(middlewareType != null, MiddlewareAlertConfig::getMiddlewareType, middlewareType)
                        .orderByAsc(MiddlewareAlertConfig::getMiddlewareType)
                        .orderByAsc(MiddlewareAlertConfig::getMetricName));
    }

    public MiddlewareAlertConfig detail(Long id) {
        return alertConfigMapper.selectById(id);
    }

    public void create(MiddlewareAlertConfig config) {
        alertConfigMapper.insert(config);
        configCache = null;
    }

    public void update(MiddlewareAlertConfig config) {
        alertConfigMapper.updateById(config);
        configCache = null;
    }

    public void delete(Long id) {
        alertConfigMapper.deleteById(id);
        configCache = null;
    }

    public List<Map<String, Object>> checkAlerts(String middlewareType, List<Map<String, Object>> instances) {
        List<MiddlewareAlertConfig> configs = loadEnabledConfigs(middlewareType);
        if (configs.isEmpty()) {
            for (var inst : instances) {
                inst.put("alertLevel", "normal");
                inst.put("alertDetails", List.of());
            }
            return instances;
        }

        Map<String, List<MiddlewareAlertConfig>> configsByMetric = configs.stream()
                .collect(Collectors.groupingBy(MiddlewareAlertConfig::getMetricName));

        for (var inst : instances) {
            String instanceId = getString(inst, "instanceId");
            String bucketName = getString(inst, "bucketName");
            String instKey = instanceId != null ? instanceId : bucketName;

            List<Map<String, Object>> alertDetails = new ArrayList<>();
            String maxLevel = "normal";

            for (var entry : configsByMetric.entrySet()) {
                String metricName = entry.getKey();
                Object rawValue = inst.get(metricName);
                if (rawValue == null) continue;

                double value;
                if (rawValue instanceof Number) {
                    value = ((Number) rawValue).doubleValue();
                } else {
                    try {
                        value = Double.parseDouble(rawValue.toString());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                }

                for (var config : entry.getValue()) {
                    if (config.getInstanceId() != null && !config.getInstanceId().isBlank()
                            && !config.getInstanceId().equals(instKey)) {
                        continue;
                    }

                    boolean triggered = isTriggered(value, config);
                    if (!triggered) continue;

                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("metricName", metricName);
                    detail.put("currentValue", Math.round(value * 100.0) / 100.0);
                    detail.put("redThreshold", config.getRedThreshold());
                    detail.put("yellowThreshold", config.getYellowThreshold());
                    detail.put("compareType", config.getCompareType());

                    boolean isRed = isRedLevel(value, config);
                    detail.put("level", isRed ? "red" : "yellow");

                    if ("red".equals(detail.get("level"))) {
                        maxLevel = "red";
                    } else if (!"red".equals(maxLevel)) {
                        maxLevel = "yellow";
                    }

                    alertDetails.add(detail);
                }
            }

            inst.put("alertLevel", maxLevel);
            inst.put("alertDetails", alertDetails);

            // 红盘告警推送到 SSE + 钉钉（24h 防抖）
            if ("red".equals(maxLevel)) {
                String alertKey = "mw:" + middlewareType + ":" + instKey;
                Long lastSent = alertLastSent.get(alertKey);
                long nowMs = System.currentTimeMillis();
                if (lastSent == null || nowMs - lastSent > ALERT_COOLDOWN_MS) {
                    alertLastSent.put(alertKey, nowMs);
                    pushMiddlewareAlert(middlewareType, inst, alertDetails);
                }
            }
        }

        return instances;
    }

    private void pushMiddlewareAlert(String type, Map<String, Object> inst, List<Map<String, Object>> details) {
        String instName = String.valueOf(inst.getOrDefault("instanceName", inst.getOrDefault("bucketName", "")));
        long now = System.currentTimeMillis();
        String timeStr = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));

        StringBuilder md = new StringBuilder();
        md.append("### 🔴 中间件告警\n\n");
        md.append(String.format("> 类型：**%s**\n\n", type.toUpperCase()));
        md.append(String.format("> 实例：**%s**\n\n", instName));
        for (var d : details) {
            if ("red".equals(d.get("level"))) {
                md.append(String.format("> %s: **%s**（阈值 %s）\n\n",
                        d.get("metricName"), d.get("currentValue"), d.get("redThreshold")));
            }
        }
        md.append(String.format("> 时间：%s\n\n", timeStr));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "middleware_alert");
            payload.put("middlewareType", type);
            payload.put("instanceName", instName);
            payload.put("time", now);
            alertPushService.pushAlert(payload);
        } catch (Exception e) {
            log.warn("中间件告警SSE推送失败: {}", e.getMessage());
        }

        try {
            String dashboardUrl = monitorProperties.getDashboardUrl() + "/#/middleware";
            dingTalkClient.sendRobotActionCard("中间件告警", md.toString(), "查看详情", dashboardUrl, true);
        } catch (Exception e) {
            log.warn("中间件告警钉钉推送失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> alertSummary(List<Map<String, Object>> instances) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int redCount = 0, yellowCount = 0, normalCount = 0;
        List<Map<String, Object>> redItems = new ArrayList<>();
        List<Map<String, Object>> yellowItems = new ArrayList<>();

        for (var inst : instances) {
            String level = String.valueOf(inst.getOrDefault("alertLevel", "normal"));
            switch (level) {
                case "red" -> {
                    redCount++;
                    redItems.add(buildAlertItem(inst));
                }
                case "yellow" -> {
                    yellowCount++;
                    yellowItems.add(buildAlertItem(inst));
                }
                default -> normalCount++;
            }
        }

        summary.put("total", instances.size());
        summary.put("redCount", redCount);
        summary.put("yellowCount", yellowCount);
        summary.put("normalCount", normalCount);
        summary.put("redItems", redItems);
        summary.put("yellowItems", yellowItems);
        return summary;
    }

    private Map<String, Object> buildAlertItem(Map<String, Object> inst) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instanceId", inst.get("instanceId"));
        item.put("instanceName", inst.get("instanceName"));
        item.put("bucketName", inst.get("bucketName"));
        item.put("alertLevel", inst.get("alertLevel"));
        item.put("alertDetails", inst.get("alertDetails"));
        return item;
    }

    private List<MiddlewareAlertConfig> loadEnabledConfigs(String middlewareType) {
        long now = System.currentTimeMillis();
        if (configCache != null && now - configCacheTime < CONFIG_CACHE_TTL_MS) {
            return configCache.stream()
                    .filter(c -> middlewareType.equals(c.getMiddlewareType()))
                    .toList();
        }

        configCache = alertConfigMapper.selectList(
                new LambdaQueryWrapper<MiddlewareAlertConfig>()
                        .eq(MiddlewareAlertConfig::getEnabled, true));
        configCacheTime = now;

        return configCache.stream()
                .filter(c -> middlewareType.equals(c.getMiddlewareType()))
                .toList();
    }

    private boolean isTriggered(double value, MiddlewareAlertConfig config) {
        return compareValue(value, config.getYellowThreshold(), config.getCompareType());
    }

    private boolean isRedLevel(double value, MiddlewareAlertConfig config) {
        return compareValue(value, config.getRedThreshold(), config.getCompareType());
    }

    private boolean compareValue(double value, BigDecimal threshold, String compareType) {
        double t = threshold.doubleValue();
        return switch (compareType) {
            case ">" -> value > t;
            case ">=" -> value >= t;
            case "<" -> value < t;
            case "<=" -> value <= t;
            default -> value > t;
        };
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
