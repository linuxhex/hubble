package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.BizAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/biz-analysis")
@RequiredArgsConstructor
@Tag(name = "经营分析", description = "经营分析数据")
public class BizAnalysisController {

    private final BizAnalysisService bizAnalysisService;

    @GetMapping("/overview")
    @Operation(summary = "汇总卡片")
    public Result<Map<String, Object>> overview() {
        return Result.success(bizAnalysisService.dailyOverview());
    }

    @GetMapping("/monthly-trend")
    @Operation(summary = "月度趋势+环比")
    public Result<List<Map<String, Object>>> monthlyTrend() {
        return Result.success(bizAnalysisService.monthlyTrend());
    }

    @GetMapping("/daily")
    @Operation(summary = "每日订单电量")
    public Result<List<Map<String, Object>>> daily(
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(bizAnalysisService.dailyOrderEnergy(days));
    }

    @GetMapping("/scenario")
    @Operation(summary = "业务场景拆分")
    public Result<Map<String, Object>> scenario() {
        return Result.success(bizAnalysisService.scenarioBreakdown());
    }

    @GetMapping("/active-users")
    @Operation(summary = "充电最活跃用户排名")
    public Result<List<Map<String, Object>>> activeUsers(
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(bizAnalysisService.activeUsersTop(limit));
    }

    @GetMapping("/app-active")
    @Operation(summary = "小程序活跃数据")
    public Result<List<Map<String, Object>>> appActive(
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(bizAnalysisService.appActive(days));
    }

    @GetMapping("/mau-trend")
    @Operation(summary = "MAU 月活趋势（近 6 月）")
    public Result<List<Map<String, Object>>> mauTrend() {
        return Result.success(bizAnalysisService.mauTrend());
    }

    @GetMapping("/yearly-comparison")
    @Operation(summary = "年度同比对比")
    public Result<Map<String, Object>> yearlyComparison() {
        return Result.success(bizAnalysisService.yearlyComparison());
    }

    @GetMapping("/revenue-trend")
    @Operation(summary = "收入趋势+客单价+度电收入")
    public Result<List<Map<String, Object>>> revenueTrend(
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(bizAnalysisService.revenueTrend(days));
    }

    @GetMapping("/utilization-trend")
    @Operation(summary = "枪利用率趋势")
    public Result<List<Map<String, Object>>> utilizationTrend(
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(bizAnalysisService.utilizationTrend(days));
    }

    @GetMapping("/region-distribution")
    @Operation(summary = "区域分布（按城市）")
    public Result<List<Map<String, Object>>> regionDistribution(
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(bizAnalysisService.regionDistribution(days));
    }

    @GetMapping("/station-ranking")
    @Operation(summary = "站点排名 TopN")
    public Result<List<Map<String, Object>>> stationRanking(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(bizAnalysisService.stationRanking(days, limit));
    }

    @GetMapping("/hourly-distribution")
    @Operation(summary = "时段分布（按小时）")
    public Result<List<Map<String, Object>>> hourlyDistribution(
            @RequestParam(defaultValue = "7") int days) {
        return Result.success(bizAnalysisService.hourlyDistribution(days));
    }

    @GetMapping("/hourly-order-comparison")
    @Operation(summary = "今日vs昨日小时订单对比")
    public Result<Map<String, Object>> hourlyOrderComparison() {
        return Result.success(bizAnalysisService.hourlyOrderComparison());
    }

    @GetMapping("/realtime-order")
    @Operation(summary = "实时订单概览（各状态订单数）")
    public Result<Map<String, Object>> realtimeOrder() {
        return Result.success(bizAnalysisService.realtimeOrderOverview());
    }

    @GetMapping("/idle-station-ranking")
    @Operation(summary = "长时间无订单枪站排名")
    public Result<List<Map<String, Object>>> idleStationRanking(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(bizAnalysisService.idleStationRanking(days, limit));
    }
}
