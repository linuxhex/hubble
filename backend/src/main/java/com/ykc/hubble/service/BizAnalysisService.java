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

    public Map<String, Object> dailyOverview() {
        String today = LocalDate.now().minusDays(1).format(DT);
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> opRows = dorisQueryClient.query(
            "SELECT SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt = '" + today + "'");

        List<Map<String, Object>> gunRows = dorisQueryClient.query(
            "SELECT COUNT(*) as total, SUM(CASE WHEN gun_status = 2 THEN 1 ELSE 0 END) as charging " +
            "FROM internal.ads.ads_gun_status_dt_da_distributed " +
            "WHERE dt = '" + today + "'");

        List<Map<String, Object>> dauRows = dorisQueryClient.query(
            "SELECT SUM(dau_user_cnt) as dau, SUM(ad_click_user_cnt) as adClick " +
            "FROM internal.ads.ads_omp_point_ad_dau_click_di " +
            "WHERE dt = '" + today + "'");

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
        result.put("date", today);
        return result;
    }

    public List<Map<String, Object>> monthlyTrend() {
        List<Map<String, Object>> rows = dorisQueryClient.query(
            "SELECT DATE_FORMAT(dt, '%Y-%m') as month, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH) " +
            "GROUP BY DATE_FORMAT(dt, '%Y-%m') " +
            "ORDER BY month");

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> item = new LinkedHashMap<>(row);
            if (i > 0) {
                double prevOrder = ((Number) rows.get(i - 1).getOrDefault("orderCnt", 0)).doubleValue();
                double curOrder = ((Number) row.getOrDefault("orderCnt", 0)).doubleValue();
                item.put("orderGrowth", prevOrder > 0 ? Math.round((curOrder - prevOrder) / prevOrder * 10000) / 100.0 : 0);

                double prevPower = ((Number) rows.get(i - 1).getOrDefault("chargedPower", 0)).doubleValue();
                double curPower = ((Number) row.getOrDefault("chargedPower", 0)).doubleValue();
                item.put("powerGrowth", prevPower > 0 ? Math.round((curPower - prevPower) / prevPower * 10000) / 100.0 : 0);
            }
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> dailyOrderEnergy(int days) {
        if (days <= 0 || days > 90) days = 30;
        String startDate = LocalDate.now().minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt as date, order_cnt as orderCnt, charged_power as chargedPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt >= '" + startDate + "' " +
            "ORDER BY dt");
    }

    public Map<String, Object> scenarioBreakdown() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> modeRows = dorisQueryClient.query(
            "SELECT trade_mode_type as tradeMode, " +
            "SUM(record_num) as orderCnt, SUM(charged_power) as chargedPower " +
            "FROM internal.ads.ads_order_history_agg_dt_da " +
            "WHERE dt >= DATE_SUB(CURDATE(), INTERVAL 30 DAY) " +
            "GROUP BY trade_mode_type " +
            "ORDER BY trade_mode_type");
        result.put("byTradeMode", modeRows);

        String recentDt = LocalDate.now().minusDays(1).format(DT);
        List<Map<String, Object>> channelRows = dorisQueryClient.query(
            "SELECT " +
            "SUM(order_cnt_retail) as retailOrder, SUM(power_retail) as retailPower, " +
            "SUM(order_cnt_non_retail) as nonRetailOrder, SUM(power_non_retail) as nonRetailPower, " +
            "SUM(order_cnt_twjs) as twjsOrder, SUM(power_twjs) as twjsPower, " +
            "SUM(order_cnt_xdt) as xdtOrder, SUM(power_xdt) as xdtPower " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE dt = '" + recentDt + "'");
        result.put("byChannel", channelRows);

        return result;
    }

    public List<Map<String, Object>> activeUsersTop(int limit) {
        if (limit <= 0 || limit > 100) limit = 20;
        String recentDt = LocalDate.now().minusDays(1).format(DT);
        return dorisQueryClient.query(
            "SELECT user_id as userId, total_ord_cnt as orderCnt, total_price as totalPrice " +
            "FROM internal.ads.ads_recharge_user_behavir_ord_anal_dt " +
            "WHERE dt = '" + recentDt + "' " +
            "ORDER BY total_ord_cnt DESC " +
            "LIMIT " + limit);
    }

    public List<Map<String, Object>> appActive(int days) {
        if (days <= 0 || days > 90) days = 30;
        String startDate = LocalDate.now().minusDays(days).format(DT);
        return dorisQueryClient.query(
            "SELECT dt as date, dau_user_cnt as dau, ad_click_user_cnt as adClick " +
            "FROM internal.ads.ads_omp_point_ad_dau_click_di " +
            "WHERE dt >= '" + startDate + "' " +
            "ORDER BY dt");
    }

    public Map<String, Object> yearlyComparison() {
        Map<String, Object> result = new LinkedHashMap<>();
        int currentYear = LocalDate.now().getYear();
        int lastYear = currentYear - 1;

        List<Map<String, Object>> thisYearRows = dorisQueryClient.query(
            "SELECT DATE_FORMAT(dt, '%Y-%m') as month, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower, SUM(income) as income " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE YEAR(dt) = " + currentYear + " " +
            "GROUP BY DATE_FORMAT(dt, '%Y-%m') " +
            "ORDER BY month");

        List<Map<String, Object>> lastYearRows = dorisQueryClient.query(
            "SELECT DATE_FORMAT(dt, '%Y-%m') as month, " +
            "SUM(order_cnt) as orderCnt, SUM(charged_power) as chargedPower, SUM(income) as income " +
            "FROM internal.ads.ads_station_daily_operation_dt " +
            "WHERE YEAR(dt) = " + lastYear + " " +
            "GROUP BY DATE_FORMAT(dt, '%Y-%m') " +
            "ORDER BY month");

        Map<String, Map<String, Object>> lastYearMap = new LinkedHashMap<>();
        for (Map<String, Object> row : lastYearRows) {
            String month = String.valueOf(row.get("month"));
            String monthKey = month.length() >= 7 ? month.substring(5) : month;
            lastYearMap.put(monthKey, row);
        }

        List<Map<String, Object>> comparison = new ArrayList<>();
        double totalThisYearOrder = 0, totalLastYearOrder = 0;
        double totalThisYearPower = 0, totalLastYearPower = 0;

        for (Map<String, Object> row : thisYearRows) {
            String month = String.valueOf(row.get("month"));
            String monthKey = month.length() >= 7 ? month.substring(5) : month;
            Map<String, Object> lastRow = lastYearMap.get(monthKey);

            double thisOrder = ((Number) row.getOrDefault("orderCnt", 0)).doubleValue();
            double thisPower = ((Number) row.getOrDefault("chargedPower", 0)).doubleValue();
            double lastOrder = lastRow != null ? ((Number) lastRow.getOrDefault("orderCnt", 0)).doubleValue() : 0;
            double lastPower = lastRow != null ? ((Number) lastRow.getOrDefault("chargedPower", 0)).doubleValue() : 0;

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
}
