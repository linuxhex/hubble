package com.ykc.hubble.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ykc.hubble.client.DorisQueryClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizAnalysisService {

    private final DorisQueryClient dorisQueryClient;
    private final com.ykc.hubble.client.GrafanaClient grafanaClient;
    private final PageDataCacheService pageDataCacheService;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PAGE_KEY = "biz_analysis";
    private static final long MEMORY_TTL_MS = 5 * 60 * 1000;

    private volatile String cachedLatestDate;
    private volatile long cachedLatestDateTs;

    private final ConcurrentHashMap<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final Object data;
        final long timestamp;

        CacheEntry(Object data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > MEMORY_TTL_MS;
        }
    }

    @PostConstruct
    public void initCache() {
        CompletableFuture.runAsync(() -> {
            log.info("经营分析：启动预热缓存...");
            try {
                refreshAll();
                log.info("经营分析：缓存预热完成");
            } catch (Exception e) {
                log.error("经营分析：缓存预热失败", e);
            }
        });
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void refreshAll() {
        try {
            memoryCache.put("overview", new CacheEntry(doDailyOverview()));
            pageDataCacheService.save(PAGE_KEY, "overview", doDailyOverview());
        } catch (Exception e) {
            log.error("刷新 overview 缓存失败", e);
        }
        try {
            var data = doMonthlyTrend();
            memoryCache.put("monthlyTrend", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "monthlyTrend", data);
        } catch (Exception e) {
            log.error("刷新 monthlyTrend 缓存失败", e);
        }
        try {
            var data = doDailyOrderEnergy(30);
            memoryCache.put("daily30", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "daily30", data);
        } catch (Exception e) {
            log.error("刷新 daily30 缓存失败", e);
        }
        try {
            var data = doScenarioBreakdown();
            memoryCache.put("scenario", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "scenario", data);
        } catch (Exception e) {
            log.error("刷新 scenario 缓存失败", e);
        }
        try {
            var data = doActiveUsersTop(20);
            memoryCache.put("activeUsers", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "activeUsers", data);
        } catch (Exception e) {
            log.error("刷新 activeUsers 缓存失败", e);
        }
        try {
            var data = doAppActive(30);
            memoryCache.put("appActive30", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "appActive30", data);
        } catch (Exception e) {
            log.error("刷新 appActive30 缓存失败", e);
        }
        try {
            var data = doMauTrend();
            memoryCache.put("mauTrend", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "mauTrend", data);
        } catch (Exception e) {
            log.error("刷新 mauTrend 缓存失败", e);
        }
        try {
            var data = doYearlyComparison();
            memoryCache.put("yearlyComparison", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "yearlyComparison", data);
        } catch (Exception e) {
            log.error("刷新 yearlyComparison 缓存失败", e);
        }
        try {
            var data = doRevenueTrend(30);
            memoryCache.put("revenueTrend30", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "revenueTrend30", data);
        } catch (Exception e) {
            log.error("刷新 revenueTrend30 缓存失败", e);
        }
        try {
            var data = doUtilizationTrend(30);
            memoryCache.put("utilizationTrend30", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "utilizationTrend30", data);
        } catch (Exception e) {
            log.error("刷新 utilizationTrend30 缓存失败", e);
        }
        try {
            var data = doRegionDistribution(30);
            memoryCache.put("regionDist30", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "regionDist30", data);
        } catch (Exception e) {
            log.error("刷新 regionDist30 缓存失败", e);
        }
        try {
            var data = doStationRanking(30, 20);
            memoryCache.put("stationRank30", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "stationRank30", data);
        } catch (Exception e) {
            log.error("刷新 stationRank30 缓存失败", e);
        }
        try {
            var data = doHourlyDistribution(7);
            memoryCache.put("hourly7", new CacheEntry(data));
            pageDataCacheService.save(PAGE_KEY, "hourly7", data);
        } catch (Exception e) {
            log.error("刷新 hourly7 缓存失败", e);
        }
        log.info("经营分析：全部缓存刷新完成");
    }

    private String latestDate() {
        long now = System.currentTimeMillis();
        if (cachedLatestDate != null && now - cachedLatestDateTs < 600_000) {
            return cachedLatestDate;
        }
        String today = LocalDate.now().format(DT);
        String weekAgo = LocalDate.now().minusDays(7).format(DT);
        List<Map<String, Object>> rows = dorisQueryClient.query(
            "SELECT MAX(dt) as maxDt FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + weekAgo + "' AND dt <= '" + today + "'");
        if (!rows.isEmpty() && rows.get(0).get("maxDt") != null) {
            String dt = String.valueOf(rows.get(0).get("maxDt"));
            if (dt.length() >= 10) {
                cachedLatestDate = dt.substring(0, 10);
                cachedLatestDateTs = now;
                return cachedLatestDate;
            }
        }
        String fallback = LocalDate.now().minusDays(1).format(DT);
        cachedLatestDate = fallback;
        cachedLatestDateTs = now;
        return fallback;
    }

    private static double toDouble(Object val, double defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static long toLong(Object val, long defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).longValue();
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private String latestDatePlusOne() {
        return LocalDate.parse(latestDate(), DT).plusDays(1).format(DT);
    }

    @SuppressWarnings("unchecked")
    private <T> T getCachedOrRefresh(String cacheKey, String dbKey, TypeReference<T> typeRef, java.util.function.Supplier<T> loader) {
        CacheEntry entry = memoryCache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return (T) entry.data;
        }

        T dbData = pageDataCacheService.get(PAGE_KEY, dbKey, typeRef);
        if (dbData != null) {
            memoryCache.put(cacheKey, new CacheEntry(dbData));
            CompletableFuture.runAsync(() -> {
                try {
                    T fresh = loader.get();
                    memoryCache.put(cacheKey, new CacheEntry(fresh));
                    pageDataCacheService.save(PAGE_KEY, dbKey, fresh);
                } catch (Exception e) {
                    log.error("后台刷新缓存失败: {}", cacheKey, e);
                }
            });
            return dbData;
        }

        if (entry != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    T fresh = loader.get();
                    memoryCache.put(cacheKey, new CacheEntry(fresh));
                    pageDataCacheService.save(PAGE_KEY, dbKey, fresh);
                } catch (Exception e) {
                    log.error("后台刷新缓存失败: {}", cacheKey, e);
                }
            });
            return (T) entry.data;
        }

        T data = loader.get();
        memoryCache.put(cacheKey, new CacheEntry(data));
        pageDataCacheService.save(PAGE_KEY, dbKey, data);
        return data;
    }

    public Map<String, Object> dailyOverview() {
        return getCachedOrRefresh("overview", "overview",
            new TypeReference<Map<String, Object>>() {},
            this::doDailyOverview);
    }

    public List<Map<String, Object>> monthlyTrend() {
        return getCachedOrRefresh("monthlyTrend", "monthlyTrend",
            new TypeReference<List<Map<String, Object>>>() {},
            this::doMonthlyTrend);
    }

    public List<Map<String, Object>> dailyOrderEnergy(int days) {
        if (days <= 0 || days > 90) days = 30;
        final int d = days;
        String key = "daily" + d;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doDailyOrderEnergy(d));
    }

    public Map<String, Object> scenarioBreakdown() {
        return getCachedOrRefresh("scenario", "scenario",
            new TypeReference<Map<String, Object>>() {},
            this::doScenarioBreakdown);
    }

    public List<Map<String, Object>> activeUsersTop(int limit) {
        if (limit <= 0 || limit > 100) limit = 20;
        final int l = limit;
        String key = "activeUsers" + l;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doActiveUsersTop(l));
    }

    public List<Map<String, Object>> appActive(int days) {
        if (days <= 0 || days > 90) days = 30;
        final int d = days;
        String key = "appActive" + d;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doAppActive(d));
    }

    public List<Map<String, Object>> mauTrend() {
        return getCachedOrRefresh("mauTrend", "mauTrend",
            new TypeReference<List<Map<String, Object>>>() {},
            this::doMauTrend);
    }

    public Map<String, Object> yearlyComparison() {
        return getCachedOrRefresh("yearlyComparison", "yearlyComparison",
            new TypeReference<Map<String, Object>>() {},
            this::doYearlyComparison);
    }

    public List<Map<String, Object>> revenueTrend(int days) {
        if (days <= 0 || days > 90) days = 30;
        final int d = days;
        String key = "revenueTrend" + d;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doRevenueTrend(d));
    }

    public List<Map<String, Object>> utilizationTrend(int days) {
        if (days <= 0 || days > 90) days = 30;
        final int d = days;
        String key = "utilizationTrend" + d;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doUtilizationTrend(d));
    }

    public List<Map<String, Object>> regionDistribution(int days) {
        if (days <= 0 || days > 90) days = 30;
        final int d = days;
        String key = "regionDist" + d;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doRegionDistribution(d));
    }

    public List<Map<String, Object>> stationRanking(int days, int limit) {
        if (days <= 0 || days > 90) days = 30;
        if (limit <= 0 || limit > 100) limit = 20;
        final int d = days;
        final int l = limit;
        String key = "stationRank" + d + "_" + l;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doStationRanking(d, l));
    }

    public List<Map<String, Object>> hourlyDistribution(int days) {
        if (days <= 0 || days > 30) days = 7;
        final int d = days;
        String key = "hourly" + d;
        return getCachedOrRefresh(key, key,
            new TypeReference<List<Map<String, Object>>>() {},
            () -> doHourlyDistribution(d));
    }

    public Map<String, Object> hourlyOrderComparison() {
        return getCachedOrRefresh("hourlyOrderComp", "hourlyOrderComp",
            new TypeReference<Map<String, Object>>() {},
            this::doHourlyOrderComparison);
    }

    // ─── 实际查询方法（doXxx） ───

    private Map<String, Object> doDailyOverview() {
        String dt = latestDate();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> opRows = dorisQueryClient.query(
            "SELECT SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt = '" + dt + "'");

        List<Map<String, Object>> gunRows = dorisQueryClient.query(
            "SELECT COUNT(*) as total, SUM(CASE WHEN gun_status = 2 THEN 1 ELSE 0 END) as charging " +
            "FROM internal.ads.ads_gun_status_dt_da_distributed " +
            "WHERE dt = '" + dt + "'");

        List<Map<String, Object>> dauRows = dorisQueryClient.query(
            "SELECT SUM(dau_user_cnt) as dau, SUM(ad_click_user_cnt) as adClick " +
            "FROM internal.ads.ads_omp_point_ad_dau_click_di " +
            "WHERE dt = '" + dt + "'");

        if (!opRows.isEmpty()) {
            var row = opRows.get(0);
            result.put("orderCnt", row.get("orderCnt"));
            result.put("chargedPower", row.get("chargedPower"));
        }
        if (!gunRows.isEmpty()) {
            var row = gunRows.get(0);
            result.put("totalGuns", row.get("total"));
            result.put("chargingGuns", row.get("charging"));
        }
        if (!dauRows.isEmpty()) {
            var row = dauRows.get(0);
            result.put("dau", row.get("dau"));
            result.put("adClick", row.get("adClick"));
        }
        result.put("date", dt);
        return result;
    }

    private List<Map<String, Object>> doMonthlyTrend() {
        String startDate = LocalDate.parse(latestDate(), DT).minusMonths(3).withDayOfMonth(1).format(DT);
        String endDate = latestDatePlusOne();
        List<Map<String, Object>> rows = dorisQueryClient.query(
            "SELECT DATE_FORMAT(dt, '%Y-%m') as monthStr, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY DATE_FORMAT(dt, '%Y-%m') " +
            "ORDER BY monthStr");

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> item = new LinkedHashMap<>(row);
            if (i > 0) {
                double prevOrder = toDouble(rows.get(i - 1).get("orderCnt"), 0);
                double curOrder = toDouble(row.get("orderCnt"), 0);
                item.put("orderGrowth", prevOrder > 0 ? Math.round((curOrder - prevOrder) / prevOrder * 10000) / 100.0 : 0);

                double prevPower = toDouble(rows.get(i - 1).get("chargedPower"), 0);
                double curPower = toDouble(row.get("chargedPower"), 0);
                item.put("powerGrowth", prevPower > 0 ? Math.round((curPower - prevPower) / prevPower * 10000) / 100.0 : 0);
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> doDailyOrderEnergy(int days) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt as statDate, SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY dt ORDER BY dt");
    }

    private Map<String, Object> doScenarioBreakdown() {
        Map<String, Object> result = new LinkedHashMap<>();
        String ld = latestDate();

        String modeStart = LocalDate.parse(ld, DT).minusDays(30).format(DT);
        String modeEnd = latestDatePlusOne();
        List<Map<String, Object>> modeRows = dorisQueryClient.query(
            "SELECT trade_mode_type as tradeMode, " +
            "SUM(record_num) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_order_history_agg_dt_da " +
            "WHERE dt >= '" + modeStart + "' AND dt < '" + modeEnd + "' " +
            "GROUP BY trade_mode_type " +
            "ORDER BY trade_mode_type");
        result.put("byTradeMode", modeRows);

        List<Map<String, Object>> channelRows = dorisQueryClient.query(
            "SELECT " +
            "SUM(order_cnt_retail) as retailOrder, SUM(power_retail) as retailPower, " +
            "SUM(order_cnt_non_retail) as nonRetailOrder, SUM(power_non_retail) as nonRetailPower, " +
            "SUM(order_cnt_twjs) as twjsOrder, SUM(power_twjs) as twjsPower, " +
            "SUM(order_cnt_xdt) as xdtOrder, SUM(power_xdt) as xdtPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt = '" + ld + "'");
        result.put("byChannel", channelRows);

        return result;
    }

    private List<Map<String, Object>> doActiveUsersTop(int limit) {
        String recentDt = latestDate();
        return dorisQueryClient.query(
            "SELECT user_id as userId, total_ord_cnt as orderCnt, total_price as totalPrice " +
            "FROM internal.ads.ads_recharge_user_behavir_ord_anal_dt " +
            "WHERE dt = '" + recentDt + "' " +
            "ORDER BY total_ord_cnt DESC " +
            "LIMIT " + limit);
    }

    private List<Map<String, Object>> doAppActive(int days) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt as statDate, dau_user_cnt as dau, ad_click_user_cnt as adClick " +
            "FROM internal.ads.ads_omp_point_ad_dau_click_di " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "ORDER BY dt");
    }

    private List<Map<String, Object>> doMauTrend() {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusMonths(6).withDayOfMonth(1).format(DT);
        List<Map<String, Object>> dailyRows = dorisQueryClient.query(
            "SELECT dt as statDate, dau_user_cnt as dau " +
            "FROM internal.ads.ads_omp_point_ad_dau_click_di " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "ORDER BY dt");

        Map<String, List<Double>> monthlyDau = new LinkedHashMap<>();
        for (Map<String, Object> row : dailyRows) {
            String dateStr = String.valueOf(row.get("statDate"));
            if (dateStr.length() >= 7) {
                String month = dateStr.substring(0, 7);
                double dau = toDouble(row.get("dau"), 0);
                monthlyDau.computeIfAbsent(month, k -> new ArrayList<>()).add(dau);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : monthlyDau.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", entry.getKey());
            List<Double> daus = entry.getValue();
            double avgDau = daus.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            item.put("mau", Math.round(avgDau));
            item.put("days", daus.size());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> doYearlyComparison() {
        Map<String, Object> result = new LinkedHashMap<>();
        String ld = latestDate();
        String ldPlus1 = latestDatePlusOne();
        int currentYear = LocalDate.parse(ld, DT).getYear();
        int lastYear = currentYear - 1;

        String thisYearStart = currentYear + "-01-01";
        List<Map<String, Object>> thisYearRows = dorisQueryClient.query(
            "SELECT DATE_FORMAT(dt, '%Y-%m') as monthStr, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower, SUM(income) as income " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + thisYearStart + "' AND dt < '" + ldPlus1 + "' " +
            "GROUP BY DATE_FORMAT(dt, '%Y-%m') " +
            "ORDER BY monthStr");

        String lastYearStart = lastYear + "-01-01";
        String lastYearEnd = lastYear + LocalDate.parse(ld, DT).format(DateTimeFormatter.ofPattern("-MM-dd"));
        List<Map<String, Object>> lastYearRows = dorisQueryClient.query(
            "SELECT DATE_FORMAT(dt, '%Y-%m') as monthStr, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower, SUM(income) as income " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + lastYearStart + "' AND dt <= '" + lastYearEnd + "' " +
            "GROUP BY DATE_FORMAT(dt, '%Y-%m') " +
            "ORDER BY monthStr");

        Map<String, Map<String, Object>> lastYearMap = new LinkedHashMap<>();
        for (Map<String, Object> row : lastYearRows) {
            String monthVal = String.valueOf(row.get("monthStr"));
            String monthKey = monthVal.length() >= 7 ? monthVal.substring(5) : monthVal;
            lastYearMap.put(monthKey, row);
        }

        List<Map<String, Object>> comparison = new ArrayList<>();
        double totalThisYearOrder = 0, totalLastYearOrder = 0;
        double totalThisYearPower = 0, totalLastYearPower = 0;

        for (Map<String, Object> row : thisYearRows) {
            String monthVal = String.valueOf(row.get("monthStr"));
            String monthKey = monthVal.length() >= 7 ? monthVal.substring(5) : monthVal;
            Map<String, Object> lastRow = lastYearMap.get(monthKey);

            double thisOrder = toDouble(row.get("orderCnt"), 0);
            double thisPower = toDouble(row.get("chargedPower"), 0);
            double lastOrder = lastRow != null ? toDouble(lastRow.get("orderCnt"), 0) : 0;
            double lastPower = lastRow != null ? toDouble(lastRow.get("chargedPower"), 0) : 0;

            totalThisYearOrder += thisOrder;
            totalThisYearPower += thisPower;
            totalLastYearOrder += lastOrder;
            totalLastYearPower += lastPower;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("month", monthKey);
            item.put("thisYearOrder", thisOrder);
            item.put("thisYearPower", thisPower);
            item.put("lastYearOrder", lastOrder);
            item.put("lastYearPower", lastPower);
            item.put("orderYoy", lastOrder > 0 ? Math.round((thisOrder - lastOrder) / lastOrder * 10000) / 100.0 : null);
            item.put("powerYoy", lastPower > 0 ? Math.round((thisPower - lastPower) / lastPower * 10000) / 100.0 : null);
            comparison.add(item);
        }

        result.put("currentYear", currentYear);
        result.put("lastYear", lastYear);
        result.put("comparison", comparison);
        result.put("totalThisYearOrder", totalThisYearOrder);
        result.put("totalLastYearOrder", totalLastYearOrder);
        result.put("orderYoy", totalLastYearOrder > 0 ? Math.round((totalThisYearOrder - totalLastYearOrder) / totalLastYearOrder * 10000) / 100.0 : null);
        result.put("totalThisYearPower", totalThisYearPower);
        result.put("totalLastYearPower", totalLastYearPower);
        result.put("powerYoy", totalLastYearPower > 0 ? Math.round((totalThisYearPower - totalLastYearPower) / totalLastYearPower * 10000) / 100.0 : null);
        return result;
    }

    private List<Map<String, Object>> doRevenueTrend(int days) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        List<Map<String, Object>> rows = dorisQueryClient.query(
            "SELECT dt as statDate, SUM(order_total_fee) as income, SUM(record_num) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_order_history_agg_dt_da " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY dt ORDER BY dt");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            double income = toDouble(row.get("income"), 0);
            double orderCnt = toDouble(row.get("orderCnt"), 0);
            double power = toDouble(row.get("chargedPower"), 0);
            item.put("avgOrderValue", orderCnt > 0 ? Math.round(income / orderCnt * 100) / 100.0 : 0);
            item.put("revenuePerKwh", power > 0 ? Math.round(income / power * 10000) / 100.0 : 0);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> doUtilizationTrend(int days) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        List<Map<String, Object>> rows = dorisQueryClient.query(
            "SELECT dt as statDate, " +
            "COUNT(*) as totalGuns, " +
            "SUM(CASE WHEN gun_status = 2 THEN 1 ELSE 0 END) as chargingGuns " +
            "FROM internal.ads.ads_gun_status_dt_da_distributed " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY dt ORDER BY dt");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            double total = toDouble(row.get("totalGuns"), 0);
            double charging = toDouble(row.get("chargingGuns"), 0);
            item.put("utilizationRate", total > 0 ? Math.round(charging / total * 10000) / 100.0 : 0);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> doRegionDistribution(int days) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT city_name as region, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY city_name ORDER BY orderCnt DESC LIMIT 20");
    }

    private List<Map<String, Object>> doStationRanking(int days, int limit) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT station_name as stationName, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY station_name ORDER BY orderCnt DESC LIMIT " + limit);
    }

    private List<Map<String, Object>> doHourlyDistribution(int days) {
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt_hour as `hour`, " +
            "SUM(record_num) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_order_history_agg_dt_da " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY dt_hour ORDER BY dt_hour");
    }

    private Map<String, Object> doHourlyOrderComparison() {
        String today = latestDate();
        String yesterday = LocalDate.parse(today, DT).minusDays(1).format(DT);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todayDate", today);
        result.put("yesterdayDate", yesterday);

        List<Map<String, Object>> todayRows = dorisQueryClient.query(
            "SELECT dt_hour as `hour`, SUM(record_num) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_order_history_agg_dt_da " +
            "WHERE dt = '" + today + "' GROUP BY dt_hour ORDER BY dt_hour");

        List<Map<String, Object>> yesterdayRows = dorisQueryClient.query(
            "SELECT dt_hour as `hour`, SUM(record_num) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_order_history_agg_dt_da " +
            "WHERE dt = '" + yesterday + "' GROUP BY dt_hour ORDER BY dt_hour");

        Map<Integer, double[]> todayMap = new LinkedHashMap<>();
        for (var row : todayRows) {
            int h = (int) toDouble(row.get("hour"), 0);
            todayMap.put(h, new double[]{toDouble(row.get("orderCnt"), 0), toDouble(row.get("chargedPower"), 0)});
        }
        Map<Integer, double[]> yesterdayMap = new LinkedHashMap<>();
        for (var row : yesterdayRows) {
            int h = (int) toDouble(row.get("hour"), 0);
            yesterdayMap.put(h, new double[]{toDouble(row.get("orderCnt"), 0), toDouble(row.get("chargedPower"), 0)});
        }

        List<Map<String, Object>> hours = new ArrayList<>();
        int alertCount = 0;
        for (int h = 0; h < 24; h++) {
            double tOrder = todayMap.containsKey(h) ? todayMap.get(h)[0] : 0;
            double tPower = todayMap.containsKey(h) ? todayMap.get(h)[1] : 0;
            double yOrder = yesterdayMap.containsKey(h) ? yesterdayMap.get(h)[0] : 0;
            double yPower = yesterdayMap.containsKey(h) ? yesterdayMap.get(h)[1] : 0;
            boolean alert = (yOrder > 0 && tOrder < yOrder * 0.5) || (yPower > 0 && tPower < yPower * 0.5);
            if (alert) alertCount++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", h);
            item.put("todayOrder", tOrder);
            item.put("yesterdayOrder", yOrder);
            item.put("todayPower", tPower);
            item.put("yesterdayPower", yPower);
            item.put("alert", alert);
            hours.add(item);
        }
        result.put("hours", hours);
        result.put("alertCount", alertCount);
        return result;
    }

    public Map<String, Object> realtimeOrderOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String bizDs = grafanaClient.getBizDsUid();
            var rows = grafanaClient.queryInstant("realtimeOrder", bizDs);
            for (var row : rows) {
                String id = String.valueOf(row.getOrDefault("id", ""));
                String name = String.valueOf(row.getOrDefault("name", ""));
                double value = toDouble(row.get("value"), 0);
                result.put(name, value);
                result.put("id_" + id, value);
            }
            result.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            log.error("查询实时订单概览失败: {}", e.getMessage());
        }
        return result;
    }

    public List<Map<String, Object>> idleStationRanking(int days, int limit) {
        if (days <= 0 || days > 90) days = 30;
        if (limit <= 0 || limit > 100) limit = 20;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT station_name as stationName, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY station_name ORDER BY orderCnt ASC LIMIT " + limit);
    }
}
