package com.ykc.hubble.service;

import com.ykc.hubble.client.DorisQueryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizAnalysisService {

    private final DorisQueryClient dorisQueryClient;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private volatile String cachedLatestDate;
    private volatile long cachedLatestDateTs;

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

    public Map<String, Object> dailyOverview() {
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

    public List<Map<String, Object>> monthlyTrend() {
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

    public List<Map<String, Object>> dailyOrderEnergy(int days) {
        if (days <= 0 || days > 90) days = 30;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt as statDate, SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY dt ORDER BY dt");
    }

    public Map<String, Object> scenarioBreakdown() {
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

    public List<Map<String, Object>> activeUsersTop(int limit) {
        if (limit <= 0 || limit > 100) limit = 20;
        String recentDt = latestDate();
        return dorisQueryClient.query(
            "SELECT user_id as userId, total_ord_cnt as orderCnt, total_price as totalPrice " +
            "FROM internal.ads.ads_recharge_user_behavir_ord_anal_dt " +
            "WHERE dt = '" + recentDt + "' " +
            "ORDER BY total_ord_cnt DESC " +
            "LIMIT " + limit);
    }

    public List<Map<String, Object>> appActive(int days) {
        if (days <= 0 || days > 90) days = 30;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt as statDate, dau_user_cnt as dau, ad_click_user_cnt as adClick " +
            "FROM internal.ads.ads_omp_point_ad_dau_click_di " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "ORDER BY dt");
    }

    public List<Map<String, Object>> mauTrend() {
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

    public Map<String, Object> yearlyComparison() {
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

    /**
     * 收入分析：近 N 日收入趋势 + 客单价 + 度电收入
     */
    public List<Map<String, Object>> revenueTrend(int days) {
        if (days <= 0 || days > 90) days = 30;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        List<Map<String, Object>> rows = dorisQueryClient.query(
            "SELECT dt as statDate, SUM(income) as income, SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
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

    /**
     * 枪利用率趋势：近 N 日充电枪数/总枪数
     */
    public List<Map<String, Object>> utilizationTrend(int days) {
        if (days <= 0 || days > 90) days = 30;
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

    /**
     * 区域分布：按城市统计订单量/电量/收入（近 N 日）
     */
    public List<Map<String, Object>> regionDistribution(int days) {
        if (days <= 0 || days > 90) days = 30;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT city_name as region, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower, SUM(income) as income " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY city_name ORDER BY orderCnt DESC LIMIT 20");
    }

    /**
     * 站点排名：Top N 站点按订单量（近 N 日）
     */
    public List<Map<String, Object>> stationRanking(int days, int limit) {
        if (days <= 0 || days > 90) days = 30;
        if (limit <= 0 || limit > 100) limit = 20;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT station_name as stationName, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower, SUM(income) as income " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY station_name ORDER BY orderCnt DESC LIMIT " + limit);
    }

    /**
     * 时段分布：按小时统计订单量/电量（近 N 日，从日表按 dt 的小时部分聚合）
     */
    public List<Map<String, Object>> hourlyDistribution(int days) {
        if (days <= 0 || days > 30) days = 7;
        String endDate = latestDatePlusOne();
        String startDate = LocalDate.parse(latestDate(), DT).minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT hour(dt) as hour, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' AND dt < '" + endDate + "' " +
            "GROUP BY hour(dt) ORDER BY hour");
    }
}
