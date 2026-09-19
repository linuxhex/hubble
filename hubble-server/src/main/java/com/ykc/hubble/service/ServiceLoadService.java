package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ykc.hubble.client.GrafanaClient;
import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.entity.ServiceLoadDaily;
import com.ykc.hubble.mapper.ServiceLoadDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceLoadService {

    private final SlsQueryClient slsQueryClient;
    private final GrafanaClient grafanaClient;
    private final MonitorProperties monitorProperties;
    private final ServiceLoadDailyMapper serviceLoadDailyMapper;

    @Value("${service-load.app-map:}")
    private String appMapStr;

    @Value("${service-load.fallback-services:}")
    private String fallbackServicesStr;

    private Map<String, String> promAppBySls = Map.of();
    private Map<String, String> slsByProm = Map.of();
    private List<String> fallbackServices = List.of();

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @PostConstruct
    public void init() {
        // 解析 SLS容器名 → Prometheus application 映射
        Map<String, String> map = new HashMap<>();
        if (appMapStr != null && !appMapStr.isBlank()) {
            for (String entry : appMapStr.split(",")) {
                String[] kv = entry.trim().split(":");
                if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
            }
        }
        promAppBySls = Collections.unmodifiableMap(map);

        Map<String, String> reverse = new HashMap<>();
        map.forEach((sls, prom) -> reverse.putIfAbsent(prom, sls));
        slsByProm = Collections.unmodifiableMap(reverse);

        // 解析备用服务列表
        if (fallbackServicesStr != null && !fallbackServicesStr.isBlank()) {
            fallbackServices = Arrays.stream(fallbackServicesStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        } else {
            fallbackServices = List.of();
        }
        log.info("服务负载配置加载完成: {} 个别名映射, {} 个备用服务", promAppBySls.size(), fallbackServices.size());
    }

    public List<ServiceLoadDaily> queryByAppNameAndDateRange(String appName, LocalDate startDate) {
        return serviceLoadDailyMapper.selectByAppNameAndDateRange(appName, startDate);
    }

    public List<ServiceLoadDaily> queryAllByDateRange(LocalDate startDate) {
        return serviceLoadDailyMapper.selectAllByDateRange(startDate);
    }

    public List<String> listAppNames() {
        return serviceLoadDailyMapper.selectDistinctAppNames();
    }

    @Scheduled(cron = "0 0 0,12 * * ?")
    public void scheduledCollect() {
        LocalTime now = LocalTime.now(ZONE);
        LocalDate targetDate = now.getHour() < 12 ? LocalDate.now(ZONE).minusDays(1) : LocalDate.now(ZONE);
        log.info("定时采集服务负载数据(SLS): date={}, hour={}", targetDate, now.getHour());
        try {
            collectFromSls(targetDate);
            cleanupOldData();
        } catch (Exception e) {
            log.error("采集服务负载数据失败: date={}", targetDate, e);
        }
    }

    public void manualCollect(LocalDate date) {
        log.info("手动触发采集服务负载数据(SLS): date={}", date);
        try {
            collectFromSls(date);
        } catch (Exception e) {
            log.error("手动采集服务负载数据失败: date={}", date, e);
            throw new RuntimeException("采集失败: " + e.getMessage(), e);
        }
    }

    public void generateDemoData() throws Exception {
        List<String> services = discoverServices();
        if (services.isEmpty()) {
            log.warn("无可用服务列表，无法生成演示数据");
            return;
        }

        LocalDate endDate = LocalDate.now(ZONE);
        LocalDate startDate = endDate.minusDays(30);
        log.info("开始生成演示数据: {} 个应用, {} 到 {}", services.size(), startDate, endDate);

        int totalInserted = 0;
        Random random = new Random(42);

        for (String appName : services) {
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (date.getDayOfMonth() % 2 != 0) continue;

                ServiceLoadDaily load = new ServiceLoadDaily();
                load.setAppName(appName);
                load.setStatDate(date);
                load.setPartialDay(0);

                double baseCpu = 20 + random.nextDouble() * 40;
                double avgCpu = Math.max(5, Math.min(95, baseCpu + random.nextGaussian() * 10));
                double maxCpu = Math.min(100, avgCpu * (1.2 + random.nextDouble() * 0.3));

                double baseMemory = 40 + random.nextDouble() * 30;
                double avgMemory = Math.max(10, Math.min(95, baseMemory + random.nextGaussian() * 5));
                double maxMemory = Math.min(100, avgMemory * (1.1 + random.nextDouble() * 0.15));

                double baseQps = 10 + random.nextDouble() * 150;
                double maxQps = Math.max(1, baseQps + random.nextGaussian() * 20);
                double avgRt = 5 + random.nextDouble() * 80;

                load.setAvgCpu(BigDecimal.valueOf(avgCpu).setScale(2, RoundingMode.HALF_UP));
                load.setMaxCpu(BigDecimal.valueOf(maxCpu).setScale(2, RoundingMode.HALF_UP));
                load.setAvgMemory(BigDecimal.valueOf(avgMemory).setScale(2, RoundingMode.HALF_UP));
                load.setMaxMemory(BigDecimal.valueOf(maxMemory).setScale(2, RoundingMode.HALF_UP));
                load.setMaxQps(BigDecimal.valueOf(maxQps).setScale(2, RoundingMode.HALF_UP));
                load.setAvgRt(BigDecimal.valueOf(avgRt).setScale(2, RoundingMode.HALF_UP));
                load.setTotalCount((long) (maxQps * 3600 * (0.5 + random.nextDouble() * 0.5)));

                upsertLoad(load);
                totalInserted++;
            }
        }
        log.info("演示数据生成完成: {} 条记录", totalInserted);
    }

    public Map<String, Object> discoverServicesFromSls() {
        Map<String, Object> result = new LinkedHashMap<>();
        String logstore = monitorProperties.getDefaultQueryLogstore();
        result.put("logstore", logstore);

        long now = System.currentTimeMillis() / 1000;
        long from = now - 3600;
        long to = now;

        try {
            List<LogEntry> rawLogs = slsQueryClient.queryLogstore(logstore, "*", from, to, 0, 1000);
            result.put("rawLogCount", rawLogs.size());

            Set<String> containerNames = new LinkedHashSet<>();
            for (LogEntry entry : rawLogs) {
                if (entry.getContainerName() != null && !entry.getContainerName().isBlank()) {
                    containerNames.add(entry.getContainerName());
                }
            }
            result.put("discoveredContainers", new ArrayList<>(containerNames));

            List<Map<String, String>> analyticsResult = slsQueryClient.queryAnalytics(
                    logstore,
                    "* | SELECT count(*) as total",
                    from, to, 10);
            result.put("analyticsTest", analyticsResult);

            if (!containerNames.isEmpty()) {
                String testContainer = containerNames.iterator().next();
                List<Map<String, Object>> filterTests = new ArrayList<>();

                String searchFilter = String.format("__tag__:_container_name_: \"%s\" | SELECT count(*) as cnt", testContainer);
                try {
                    List<Map<String, String>> r1 = slsQueryClient.queryAnalytics(logstore, searchFilter, from, to, 10);
                    filterTests.add(Map.of("syntax", "search_filter", "query", searchFilter, "result", r1));
                } catch (Exception e) {
                    filterTests.add(Map.of("syntax", "search_filter", "query", searchFilter, "error", e.getMessage()));
                }

                String sqlWhere = String.format("* | SELECT count(*) as cnt WHERE \"__tag__:_container_name_\" = '%s'", testContainer);
                try {
                    List<Map<String, String>> r2 = slsQueryClient.queryAnalytics(logstore, sqlWhere, from, to, 10);
                    filterTests.add(Map.of("syntax", "sql_where_single_quote", "query", sqlWhere, "result", r2));
                } catch (Exception e) {
                    filterTests.add(Map.of("syntax", "sql_where_single_quote", "query", sqlWhere, "error", e.getMessage()));
                }

                String sqlLike = String.format("* | SELECT count(*) as cnt WHERE \"__tag__:_container_name_\" LIKE '%%%s%%'", testContainer);
                try {
                    List<Map<String, String>> r3 = slsQueryClient.queryAnalytics(logstore, sqlLike, from, to, 10);
                    filterTests.add(Map.of("syntax", "sql_like", "query", sqlLike, "result", r3));
                } catch (Exception e) {
                    filterTests.add(Map.of("syntax", "sql_like", "query", sqlLike, "error", e.getMessage()));
                }

                result.put("filterTests", filterTests);
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return result;
    }

    int collectFromSls(LocalDate targetDate) throws Exception {
        String logstore = monitorProperties.getDefaultQueryLogstore();

        LinkedHashMap<String, String> services = discoverServicesWithRaw();
        if (services.isEmpty()) {
            log.warn("服务发现为空，跳过采集: date={}", targetDate);
            return 0;
        }
        log.info("服务发现 {} 个: {}", services.size(), services.keySet());

        long peakFrom = toEpochSecond(targetDate, LocalTime.of(9, 0));
        long peakTo = toEpochSecond(targetDate, LocalTime.of(11, 0));
        long peakFrom2 = toEpochSecond(targetDate, LocalTime.of(14, 0));
        long peakTo2 = toEpochSecond(targetDate, LocalTime.of(17, 0));

        long dayFrom = targetDate.atStartOfDay(ZONE).toEpochSecond();
        long dayTo = targetDate.plusDays(1).atStartOfDay(ZONE).toEpochSecond();

        boolean partialDay = targetDate.equals(LocalDate.now(ZONE));
        int collected = 0;
        Map<String, CpuMemResult> cpuMemMap = queryDayCpuMem(targetDate);

        for (Map.Entry<String, String> entry : services.entrySet()) {
            String appName = entry.getKey();
            String slsName = entry.getValue();
            try {
                ServiceLoadDaily load = new ServiceLoadDaily();
                load.setAppName(appName);
                load.setStatDate(targetDate);
                load.setPartialDay(partialDay ? 1 : 0);

                CpuMemResult cpuMem = cpuMemMap.get(appName);
                if (cpuMem != null) {
                    load.setAvgCpu(cpuMem.avgCpu);
                    load.setMaxCpu(cpuMem.maxCpu);
                    load.setAvgMemory(cpuMem.avgMem);
                    load.setMaxMemory(cpuMem.maxMem);
                }

                PeakResult peak = queryPeakQps(logstore, slsName, peakFrom, peakTo, peakFrom2, peakTo2);
                load.setMaxQps(peak.maxQps);
                load.setTotalCount(peak.totalCount);
                load.setAvgRt(peak.avgRt);

                if (peak.totalCount == 0) {
                    DayResult day = queryDayStats(logstore, slsName, dayFrom, dayTo);
                    load.setTotalCount(day.totalCount);
                    load.setMaxQps(day.maxQps);
                    if (load.getAvgRt() == null) {
                        load.setAvgRt(day.avgRt);
                    }
                }

                // 指标全缺失时不落库，避免0值污染
                boolean hasCpuMem = load.getMaxCpu() != null || load.getMaxMemory() != null;
                boolean hasTraffic = load.getTotalCount() != null && load.getTotalCount() > 0;
                if (!hasCpuMem && !hasTraffic) {
                    log.debug("服务无任何负载数据，跳过落库: service={}, date={}", appName, targetDate);
                    continue;
                }

                upsertLoad(load);
                collected++;
            } catch (Exception e) {
                log.warn("采集服务负载失败: service={}, err={}", appName, e.getMessage());
            }
        }

        log.info("服务负载采集完成: date={}, collected={}/{}", targetDate, collected, services.size());
        return collected;
    }

    /**
     * 从 Prometheus application 标签自动发现应用
     */
    private Set<String> discoverApplicationsFromProm() {
        Set<String> apps = new LinkedHashSet<>();
        String ds = grafanaClient.getDsUid();
        String[] promqls = {
                "count by (application) (system_cpu_usage)",
                "count by (application) (jvm_memory_used_bytes{area=\"heap\"})"
        };
        for (String promql : promqls) {
            try {
                List<Map<String, Object>> rows = grafanaClient.queryInstant(promql, ds);
                for (Map<String, Object> row : rows) {
                    Object app = row.get("application");
                    if (app == null) continue;
                    String name = String.valueOf(app).trim();
                    if (name.isEmpty() || "unknown".equalsIgnoreCase(name) || "null".equalsIgnoreCase(name)) continue;
                    apps.add(name);
                }
            } catch (Exception e) {
                log.warn("Prometheus 应用自动发现失败: promql={}, err={}", promql, e.getMessage());
            }
        }
        return apps;
    }

    /**
     * 四级服务发现（Prom → SLS → DB → 备用列表），返回 归一化应用名 → SLS容器原始名
     */
    private LinkedHashMap<String, String> discoverServicesWithRaw() {
        LinkedHashMap<String, String> services = new LinkedHashMap<>();

        Set<String> promApps = discoverApplicationsFromProm();
        for (String app : promApps) {
            addAlias(services, app, app);
        }
        if (!promApps.isEmpty()) {
            log.info("从Prometheus发现 {} 个应用: {}", promApps.size(), promApps);
        }

        String logstore = monitorProperties.getDefaultQueryLogstore();
        long now = System.currentTimeMillis() / 1000;
        try {
            List<LogEntry> rawLogs = slsQueryClient.queryLogstore(logstore, "*", now - 3600, now, 0, 500);
            int slsCount = 0;
            for (LogEntry entry : rawLogs) {
                String container = entry.getContainerName();
                if (container == null || container.isBlank()) continue;
                container = container.trim();
                String norm = promAppBySls.getOrDefault(container, container);
                // 已有条目刷新为真实容器名，保证QPS过滤用对名字
                services.put(norm, container);
                slsCount++;
            }
            if (slsCount > 0) {
                log.info("从SLS发现 {} 个容器", slsCount);
            }
        } catch (Exception e) {
            log.warn("从SLS发现服务失败: {}", e.getMessage());
        }

        try {
            List<String> dbApps = serviceLoadDailyMapper.selectDistinctAppNames();
            if (dbApps != null) {
                for (String app : dbApps) {
                    if (app == null || app.isBlank()) continue;
                    String norm = app.trim();
                    addAlias(services, norm, slsByProm.getOrDefault(norm, norm));
                }
            }
        } catch (Exception e) {
            log.warn("从DB查询历史应用失败: {}", e.getMessage());
        }

        for (String app : fallbackServices) {
            addAlias(services, app, app);
        }

        return services;
    }

    private boolean addAlias(LinkedHashMap<String, String> services, String normName, String slsRaw) {
        if (normName == null || normName.isBlank()) return false;
        String norm = normName.trim();
        return services.putIfAbsent(norm, slsRaw == null ? norm : slsRaw) == null;
    }

    private List<String> discoverServices() {
        LinkedHashMap<String, String> services = discoverServicesWithRaw();
        return new ArrayList<>(services.keySet());
    }

    private PeakResult queryPeakQps(String logstore, String service,
                                     long from1, long to1, long from2, long to2) {
        PeakResult result = new PeakResult();

        String filter = String.format("__tag__:_container_name_: \"%s\"", service);
        String sql = " | SELECT date_format(__time__ - __time__ % 300, '%H:%i') as time_slot, "
                + "count(*) as cnt GROUP BY time_slot ORDER BY time_slot";
        String query = filter + sql;

        for (long[] window : new long[][]{{from1, to1}, {from2, to2}}) {
            try {
                List<Map<String, String>> rows = slsQueryClient.queryAnalytics(
                        logstore, query, window[0], window[1], 100);

                for (Map<String, String> row : rows) {
                    long cnt = parseLong(row.get("cnt"));
                    result.totalCount += cnt;

                    double qps = cnt / 300.0;
                    if (qps > result.maxQpsValue) {
                        result.maxQpsValue = qps;
                    }
                }
            } catch (Exception e) {
                log.debug("高峰时段QPS查询失败(service={}): {}", service, e.getMessage());
            }
        }

        if (result.totalCount > 0) {
            result.maxQps = BigDecimal.valueOf(result.maxQpsValue).setScale(2, RoundingMode.HALF_UP);
        }
        result.avgRt = extractAvgRt(logstore, service, from1, to2);
        return result;
    }

    private DayResult queryDayStats(String logstore, String service, long from, long to) {
        DayResult result = new DayResult();

        String filter = String.format("__tag__:_container_name_: \"%s\"", service);
        String sql = " | SELECT date_format(__time__ - __time__ % 300, '%H:%i') as time_slot, "
                + "count(*) as cnt GROUP BY time_slot ORDER BY time_slot";
        String query = filter + sql;

        try {
            List<Map<String, String>> rows = slsQueryClient.queryAnalytics(
                    logstore, query, from, to, 500);

            for (Map<String, String> row : rows) {
                long cnt = parseLong(row.get("cnt"));
                result.totalCount += cnt;

                double qps = cnt / 300.0;
                if (qps > result.maxQpsValue) {
                    result.maxQpsValue = qps;
                }
            }

            if (result.totalCount > 0) {
                result.maxQps = BigDecimal.valueOf(result.maxQpsValue).setScale(2, RoundingMode.HALF_UP);
                result.avgRt = extractAvgRt(logstore, service, from, to);
            }
        } catch (Exception e) {
            log.warn("全天统计查询失败(service={}): {}", service, e.getMessage());
        }
        return result;
    }

    private BigDecimal extractAvgRt(String logstore, String service, long from, long to) {
        String filter = String.format("__tag__:_container_name_: \"%s\" and (cost or useTime or duration or elapsed or rt)", service);
        try {
            List<LogEntry> logs = slsQueryClient.queryLogstore(
                    logstore, filter, from, to, 0, 200);
            double totalRt = 0;
            int count = 0;
            for (LogEntry entry : logs) {
                double rt = extractDuration(entry);
                if (rt > 0) {
                    totalRt += rt;
                    count++;
                }
            }
            if (count > 0) {
                return BigDecimal.valueOf(totalRt / count).setScale(2, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.debug("RT提取失败(service={}): {}", service, e.getMessage());
        }
        return null;
    }

    private double extractDuration(LogEntry entry) {
        if (entry == null) return 0;
        Map<String, String> fields = entry.getFields();
        if (fields != null) {
            for (String key : new String[]{"duration", "cost", "rt", "useTime", "elapsed"}) {
                String val = fields.get(key);
                if (val != null && !val.isBlank()) {
                    try { return Double.parseDouble(val); } catch (NumberFormatException ignored) {}
                }
            }
        }
        String message = entry.getMessage();
        if (message != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:cost|useTime|took|elapsed|rt|duration|time|耗时)[:\\s=]+(\\d+(?:\\.\\d+)?)\\s*(?:ms)?")
                    .matcher(message);
            if (m.find()) {
                try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    private Map<String, CpuMemResult> queryDayCpuMem(LocalDate date) {
        Map<String, CpuMemResult> result = new HashMap<>();
        String ds = grafanaClient.getDsUid();
        long fromSec = date.atStartOfDay(ZONE).toEpochSecond();
        long toSec = date.plusDays(1).atStartOfDay(ZONE).toEpochSecond();
        String from = Instant.ofEpochSecond(fromSec).toString();
        String to = Instant.ofEpochSecond(toSec).toString();

        String cpuPromql = "avg by (application) (system_cpu_usage)*100";
        String memPromql = "(sum by (application) (jvm_memory_used_bytes{area=\"heap\"}) * 100) / "
                + "sum by (application) (jvm_memory_max_bytes{area=\"heap\"})";

        try {
            List<Map<String, Object>> cpuRows = grafanaClient.queryRange(cpuPromql, from, to, ds);
            List<Map<String, Object>> memRows = grafanaClient.queryRange(memPromql, from, to, ds);

            Map<String, List<Double>> cpuByApp = new HashMap<>();
            for (Map<String, Object> row : cpuRows) {
                String app = String.valueOf(row.getOrDefault("application", ""));
                if (app.isEmpty()) continue;
                double val = parseDoubleObj(row.get("value"));
                if (val > 0) {
                    cpuByApp.computeIfAbsent(app, k -> new ArrayList<>()).add(val);
                }
            }

            Map<String, List<Double>> memByApp = new HashMap<>();
            for (Map<String, Object> row : memRows) {
                String app = String.valueOf(row.getOrDefault("application", ""));
                if (app.isEmpty()) continue;
                double val = parseDoubleObj(row.get("value"));
                if (val > 0) {
                    memByApp.computeIfAbsent(app, k -> new ArrayList<>()).add(val);
                }
            }

            Set<String> apps = new LinkedHashSet<>();
            apps.addAll(cpuByApp.keySet());
            apps.addAll(memByApp.keySet());
            for (String app : apps) {
                CpuMemResult r = new CpuMemResult();
                List<Double> cpuVals = cpuByApp.get(app);
                List<Double> memVals = memByApp.get(app);
                if (cpuVals != null && !cpuVals.isEmpty()) {
                    r.avgCpu = avgBig(cpuVals);
                    r.maxCpu = maxBig(cpuVals);
                }
                if (memVals != null && !memVals.isEmpty()) {
                    r.avgMem = avgBig(memVals);
                    r.maxMem = maxBig(memVals);
                }
                result.put(app, r);
            }
            log.info("Grafana CPU/内存采集完成: {} 个应用有数据", result.size());
        } catch (Exception e) {
            log.warn("Grafana CPU/内存查询失败: {}", e.getMessage());
        }
        return result;
    }

    private BigDecimal avgBig(List<Double> vals) {
        double sum = 0;
        for (double v : vals) sum += v;
        return BigDecimal.valueOf(sum / vals.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal maxBig(List<Double> vals) {
        double max = vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return BigDecimal.valueOf(max).setScale(2, RoundingMode.HALF_UP);
    }

    private double parseDoubleObj(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try { return Double.parseDouble(obj.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private void upsertLoad(ServiceLoadDaily load) {
        ServiceLoadDaily existing = serviceLoadDailyMapper.selectOne(
                new LambdaQueryWrapper<ServiceLoadDaily>()
                        .eq(ServiceLoadDaily::getAppName, load.getAppName())
                        .eq(ServiceLoadDaily::getStatDate, load.getStatDate())
        );
        if (existing == null) {
            serviceLoadDailyMapper.insert(load);
            return;
        }
        // 显式 set（含 null），避免旧值粘滞
        LambdaUpdateWrapper<ServiceLoadDaily> uw = new LambdaUpdateWrapper<ServiceLoadDaily>()
                .eq(ServiceLoadDaily::getId, existing.getId())
                .set(ServiceLoadDaily::getAvgCpu, load.getAvgCpu())
                .set(ServiceLoadDaily::getMaxCpu, load.getMaxCpu())
                .set(ServiceLoadDaily::getAvgMemory, load.getAvgMemory())
                .set(ServiceLoadDaily::getMaxMemory, load.getMaxMemory())
                .set(ServiceLoadDaily::getMaxQps, load.getMaxQps())
                .set(ServiceLoadDaily::getAvgRt, load.getAvgRt())
                .set(ServiceLoadDaily::getTotalCount, load.getTotalCount())
                .set(ServiceLoadDaily::getPartialDay, load.getPartialDay());
        serviceLoadDailyMapper.update(null, uw);
    }

    private void cleanupOldData() {
        LocalDate oneYearAgo = LocalDate.now(ZONE).minus(1, ChronoUnit.YEARS);
        int deleted = serviceLoadDailyMapper.deleteBeforeDate(oneYearAgo);
        if (deleted > 0) {
            log.info("清理超过1年的服务负载数据: deleted={}", deleted);
        }
    }

    private long toEpochSecond(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZONE).toEpochSecond();
    }

    private long parseLong(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static class PeakResult {
        BigDecimal maxQps;
        double maxQpsValue = 0;
        long totalCount = 0;
        BigDecimal avgRt;
    }

    private static class DayResult {
        BigDecimal maxQps;
        double maxQpsValue = 0;
        long totalCount = 0;
        BigDecimal avgRt;
    }

    private static class CpuMemResult {
        BigDecimal avgCpu;
        BigDecimal maxCpu;
        BigDecimal avgMem;
        BigDecimal maxMem;
    }
}
