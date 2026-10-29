package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.MiddlewareAlertConfig;
import com.ykc.hubble.mapper.MiddlewareAlertConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final MiddlewareMonitorService monitorService;
    private final AlertThresholdService thresholdService;
    private final AlertInvestigationService alertInvestigationService;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final java.util.concurrent.Executor queryExecutor;

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
                // 告警读"近5分钟峰值"（短尖峰可能被 5 分钟展示缓存+最新值逻辑跳过），无峰值键时回退最新值
                Object rawValue = inst.get(metricName + "Peak");
                if (rawValue == null) rawValue = inst.get(metricName);
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

                    boolean isRed = isRedLevel(value, config);

                    // 红色=硬水位（如内存>85%），无条件告警不做过滤——持续高水位是真实风险必须报；
                    // 黄色触发走"昨天同时段同比双条件"：涨幅 ≥ mw_yoy_surge_threshold 且当前值 ≥ mw_yoy_abs_floor
                    // 才告警，避免常规水位刷屏。昨天值不可得时跳过过滤，防漏报。
                    Map<String, Object> yoyInfo = null;
                    if (!isRed) {
                        Double yesterday = resolveYesterdayValue(inst, metricName);
                        if (yesterday != null) {
                            double surgeThreshold = thresholdService.getDouble("mw_yoy_surge_threshold", 300);
                            double absFloor = thresholdService.getDouble("mw_yoy_abs_floor", 30);
                            double surge = yesterday > 0 ? (value - yesterday) / yesterday * 100 : Double.MAX_VALUE;
                            if (surge < surgeThreshold || value < absFloor) {
                                log.info("同比过滤: {} {} {} 当前={} 昨天={} 涨幅={}（要求涨幅≥{}% 且当前值≥{}），不告警",
                                        middlewareType, instKey, metricName, round2(value), round2(yesterday),
                                        surge == Double.MAX_VALUE ? "∞" : round2(surge), surgeThreshold, absFloor);
                                continue;
                            }
                            yoyInfo = new LinkedHashMap<>();
                            yoyInfo.put("yesterdayValue", round2(yesterday));
                            yoyInfo.put("surgePercent", surge == Double.MAX_VALUE ? "∞" : round2(surge));
                        }
                    }

                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("metricName", metricName);
                    detail.put("currentValue", Math.round(value * 100.0) / 100.0);
                    detail.put("redThreshold", config.getRedThreshold());
                    detail.put("yellowThreshold", config.getYellowThreshold());
                    detail.put("compareType", config.getCompareType());
                    if (yoyInfo != null) {
                        detail.putAll(yoyInfo);
                    }

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
        StringBuilder redDetails = new StringBuilder();
        for (var d : details) {
            if ("red".equals(d.get("level"))) {
                if (redDetails.length() > 0) redDetails.append("; ");
                redDetails.append(String.format("%s=%s（阈值 %s）", d.get("metricName"), d.get("currentValue"), d.get("redThreshold")));
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("### 🔴 中间件告警\n\n");
        md.append(String.format("> 类型：**%s**\n\n", type.toUpperCase()));
        md.append(String.format("> 实例：**%s**\n\n", instName));
        for (var d : details) {
            if ("red".equals(d.get("level"))) {
                md.append(String.format("> %s: **%s**（阈值 %s）\n\n",
                        d.get("metricName"), d.get("currentValue"), d.get("redThreshold")));
                if (d.containsKey("yesterdayValue")) {
                    md.append(String.format("> 同比：昨天同时段 **%s**，涨幅 **%s%%**\n\n",
                            d.get("yesterdayValue"), d.get("surgePercent")));
                }
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
            String dashboardUrl = monitorProperties.getDashboardUrl() + "/middleware";
            dingTalkClient.sendRobotActionCard("中间件告警", md.toString(), "查看详情", dashboardUrl, true);
        } catch (Exception e) {
            log.warn("中间件告警钉钉推送失败: {}", e.getMessage());
        }

        // AI 事故调查（红盘即视为 ERROR 级；service_name 用 实例标识）
        alertInvestigationService.submit(com.ykc.hubble.service.investigation.InvestigationContext.builder()
                .alertSource("MW")
                .alertType(type + "红盘告警")
                .alertName("中间件告警")
                .serviceName(type + "/" + instName)
                .severity("ERROR")
                .alertValue(redDetails.toString())
                .alertTimeMs(now)
                .build());
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

    private String getString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * 解析指标"昨天同一时段"的值。
     * 优先读预采集键 _yesterday_&lt;metric&gt;（rocketmq/SLS、跨指标查询缓存），
     * 否则按 _yoy 元数据按需查询（结果回填，5 分钟实例缓存期内同指标只查一次）。
     * 返回 null 表示数据源不支持或无昨天数据，调用方跳过同比判断。
     */
    private Double resolveYesterdayValue(Map<String, Object> inst, String metricName) {
        Object pre = inst.get("_yesterday_" + metricName);
        if (pre instanceof Number n) return n.doubleValue();
        if (pre != null) {
            try { return Double.parseDouble(pre.toString()); } catch (NumberFormatException ignored) {}
        }

        Object metaObj = inst.get("_yoy");
        if (!(metaObj instanceof Map<?, ?> yoy)) return null;
        Object specObj = yoy.get(metricName);
        if (!(specObj instanceof Map<?, ?> spec)) return null;
        Object specStr = spec.get("spec");
        if (specStr == null || specStr.toString().isEmpty()) return null;
        try {
            Double result;
            if ("gr".equals(spec.get("src"))) {
                result = monitorService.queryYesterdayGrafanaPeak(getString(spec, "ds"), specStr.toString());
            } else {
                result = monitorService.queryYesterdayCloudMonitorPeak(getString(spec, "ns"), specStr.toString(), getString(spec, "dim"));
            }
            if (result != null) {
                inst.put("_yesterday_" + metricName, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("查询昨天同时段值失败: metric={}, err={}", metricName, e.getMessage());
            return null;
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 定时巡检：每 1 分钟检查所有中间件的告警状态，确保问题能在 1-2 分钟内被发现
     */
    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 60 * 1000)
    public void scheduledAlertCheck() {
        try {
            String[] types = {"redis", "mysql", "db", "rocketmq", "kafka", "lindorm", "elasticsearch", "oss"};

            var futures = new java.util.concurrent.CompletableFuture[types.length];
            for (int i = 0; i < types.length; i++) {
                String type = types[i];
                futures[i] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    List<Map<String, Object>> instances = fetchInstances(type);
                    if (instances == null || instances.isEmpty()) return 0L;
                    List<Map<String, Object>> checked = checkAlerts(type, instances);
                    return checked.stream().filter(inst -> "red".equals(inst.get("alertLevel"))).count();
                }, queryExecutor);
            }

            try {
                java.util.concurrent.CompletableFuture.allOf(futures)
                        .get(90 * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("定时告警巡检总超时 90s，仅收集已完成部分（防止单线程调度池被挂起阻塞）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("定时告警巡检异常: {}", e.getMessage());
            }

            long totalRed = 0;
            for (var f : futures) {
                try { totalRed += (long) f.getNow(0L); } catch (Exception ignored) {}
            }

            // Kafka 消费组级堆积告警（独立于实例级，修复实例聚合抹平消费组的盲区）
            try {
                totalRed += checkKafkaConsumerGroupAlerts();
            } catch (Exception e) {
                log.warn("Kafka 消费组告警巡检异常: {}", e.getMessage());
            }

            if (totalRed > 0) {
                log.info("定时告警巡检完成：发现 {} 个红盘告警", totalRed);
            }
        } catch (Exception e) {
            log.error("定时告警巡检异常: {}", e.getMessage(), e);
        }
    }

    /**
     * Kafka 消费组级堆积告警：按 consumerGroup 维度评估 consumerGroupLag 阈值。
     * 只告警生产环境（tag_env=prod 或 ins_name 以 prod- 开头），排除 spark-kafka-source-* 临时组。
     * 返回红盘消费组数量。
     */
    private long checkKafkaConsumerGroupAlerts() {
        List<Map<String, Object>> groups = monitorService.kafkaConsumerGroups();
        if (groups == null || groups.isEmpty()) return 0;

        // 只取 consumerGroupLag 配置（与实例级 lag 解耦）
        List<MiddlewareAlertConfig> configs = loadEnabledConfigs("kafka").stream()
                .filter(c -> "consumerGroupLag".equals(c.getMetricName()))
                .toList();
        if (configs.isEmpty()) {
            log.debug("Kafka 消费组告警：未配置 consumerGroupLag 阈值，跳过");
            return 0;
        }

        long redCount = 0;
        for (var group : groups) {
            String consumerGroup = String.valueOf(group.getOrDefault("consumerGroup", ""));
            String insName = String.valueOf(group.getOrDefault("instanceName", ""));
            String tagEnv = String.valueOf(group.getOrDefault("tagEnv", ""));
            String instanceId = String.valueOf(group.getOrDefault("instanceId", ""));

            // 告警范围过滤：只生产 + 排除 spark 临时组
            boolean isProd = "prod".equals(tagEnv) || insName.startsWith("prod-");
            boolean isSparkTemp = consumerGroup.startsWith("spark-kafka-source");
            if (!isProd || isSparkTemp) {
                group.put("alertLevel", "normal");
                group.put("alertDetails", List.of());
                continue;
            }

            double value;
            Object rawValue = group.get("lag");
            if (rawValue instanceof Number n) {
                value = n.doubleValue();
            } else {
                try { value = Double.parseDouble(String.valueOf(rawValue)); }
                catch (NumberFormatException e) { continue; }
            }

            List<Map<String, Object>> alertDetails = new ArrayList<>();
            String maxLevel = "normal";
            for (var config : configs) {
                if (!isTriggered(value, config)) continue;
                boolean isRed = isRedLevel(value, config);

                // 消费组无昨天值（resolveYesterdayValue 不支持消费组维度），按现有规则
                // "昨天值不可得跳过过滤防漏报"，粉盘直接告警
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("metricName", "consumerGroupLag");
                detail.put("currentValue", Math.round(value));
                detail.put("redThreshold", config.getRedThreshold());
                detail.put("yellowThreshold", config.getYellowThreshold());
                detail.put("compareType", config.getCompareType());
                detail.put("level", isRed ? "red" : "yellow");

                if ("red".equals(detail.get("level"))) {
                    maxLevel = "red";
                } else if (!"red".equals(maxLevel)) {
                    maxLevel = "yellow";
                }
                alertDetails.add(detail);
            }

            group.put("alertLevel", maxLevel);
            group.put("alertDetails", alertDetails);

            if ("red".equals(maxLevel)) {
                redCount++;
                String alertKey = "mw:kafka:cg:" + instanceId + ":" + consumerGroup;
                Long lastSent = alertLastSent.get(alertKey);
                long nowMs = System.currentTimeMillis();
                if (lastSent == null || nowMs - lastSent > ALERT_COOLDOWN_MS) {
                    alertLastSent.put(alertKey, nowMs);
                    pushKafkaConsumerGroupAlert(group, alertDetails);
                }
            }
        }
        return redCount;
    }

    /**
     * 推送 Kafka 消费组堆积红盘告警：钉钉 + SSE + AI 事故调查。
     */
    private void pushKafkaConsumerGroupAlert(Map<String, Object> group, List<Map<String, Object>> details) {
        String consumerGroup = String.valueOf(group.getOrDefault("consumerGroup", ""));
        String instName = String.valueOf(group.getOrDefault("instanceName", ""));
        long now = System.currentTimeMillis();
        String timeStr = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));

        StringBuilder redDetails = new StringBuilder();
        for (var d : details) {
            if ("red".equals(d.get("level"))) {
                if (redDetails.length() > 0) redDetails.append("; ");
                redDetails.append(String.format("%s=%s（阈值 %s）", d.get("metricName"), d.get("currentValue"), d.get("redThreshold")));
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("### 🔴 Kafka 消费组堆积告警\n\n");
        md.append(String.format("> 消费组：**%s**\n\n", consumerGroup));
        md.append(String.format("> 实例：**%s**\n\n", instName));
        for (var d : details) {
            if ("red".equals(d.get("level"))) {
                md.append(String.format("> 堆积量: **%s**（红盘阈值 %s）\n\n",
                        d.get("currentValue"), d.get("redThreshold")));
            }
        }
        md.append(String.format("> 时间：%s\n\n", timeStr));

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "middleware_alert");
            payload.put("middlewareType", "kafka");
            payload.put("instanceName", consumerGroup);
            payload.put("time", now);
            alertPushService.pushAlert(payload);
        } catch (Exception e) {
            log.warn("Kafka 消费组告警SSE推送失败: {}", e.getMessage());
        }

        try {
            String dashboardUrl = monitorProperties.getDashboardUrl() + "/middleware";
            dingTalkClient.sendRobotActionCard("Kafka 消费组堆积告警", md.toString(), "查看详情", dashboardUrl, true);
        } catch (Exception e) {
            log.warn("Kafka 消费组告警钉钉推送失败: {}", e.getMessage());
        }

        // AI 事故调查（红盘视为 ERROR 级；service_name 用 kafka/{consumerGroup}）
        alertInvestigationService.submit(com.ykc.hubble.service.investigation.InvestigationContext.builder()
                .alertSource("MW")
                .alertType("kafka消费组堆积")
                .alertName("Kafka 消费组堆积告警")
                .serviceName("kafka/" + consumerGroup)
                .severity("ERROR")
                .alertValue(redDetails.toString())
                .alertTimeMs(now)
                .build());
    }

    private List<Map<String, Object>> fetchInstances(String middlewareType) {
        return switch (middlewareType) {
            case "redis" -> new ArrayList<>(monitorService.redisInstances());
            case "mysql" -> new ArrayList<>(monitorService.mysqlInstances());
            case "db" -> new ArrayList<>(monitorService.dbInstances());
            case "rocketmq" -> new ArrayList<>(monitorService.rocketmqInstances());
            case "kafka" -> new ArrayList<>(monitorService.kafkaInstances());
            case "lindorm" -> new ArrayList<>(monitorService.lindormInstances());
            case "elasticsearch" -> new ArrayList<>(monitorService.elasticsearchInstances());
            case "oss" -> new ArrayList<>(monitorService.ossBuckets());
            default -> null;
        };
    }
}
