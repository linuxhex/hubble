package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.GatewayService;
import com.ykc.hubble.service.OverviewSnapshotCache;
import com.ykc.hubble.vo.ApiDegradationVO;
import com.ykc.hubble.vo.GatewayHotApiVO;
import com.ykc.hubble.vo.GatewayOverviewVO;
import com.ykc.hubble.vo.GatewayTrendVO;
import com.ykc.hubble.vo.OverviewSnapshotPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;
    private final OverviewSnapshotCache overviewSnapshotCache;

    @GetMapping("/overview")
    public Result<GatewayOverviewVO> overview(
            @RequestParam(defaultValue = "24h") String timeRange) {
        return Result.success(gatewayService.overview(timeRange));
    }

    @GetMapping("/overview/history")
    public Result<Map<String, Object>> overviewHistory(
            @RequestParam(defaultValue = "1h") String timeRange) {
        // 获取该时间范围的所有快照点
        long now = System.currentTimeMillis() / 1000;
        long from = now - parseTimeRange(timeRange);
        List<OverviewSnapshotPoint> points = overviewSnapshotCache.get(timeRange, from, now);
        
        Map<String, Object> result = new HashMap<>();
        result.put("total", points.size());
        result.put("records", points);
        return Result.success(result);
    }

    @GetMapping("/trend")
    public Result<GatewayTrendVO> trend(
            @RequestParam(defaultValue = "24h") String timeRange) {
        return Result.success(gatewayService.trend(timeRange));
    }

    @GetMapping("/hot-apis")
    public Result<List<GatewayHotApiVO>> hotApis(
            @RequestParam(defaultValue = "24h") String timeRange) {
        return Result.success(gatewayService.hotApis(timeRange));
    }

    @GetMapping("/degradation")
    public Result<List<ApiDegradationVO>> degradation(
            @RequestParam(defaultValue = "day") String compareMode) {
        return Result.success(gatewayService.degradation(compareMode));
    }

    @GetMapping("/p60-ranking")
    public Result<List<ApiDegradationVO>> p60Ranking(
            @RequestParam(defaultValue = "day") String compareMode) {
        return Result.success(gatewayService.p60Ranking(compareMode));
    }

    @GetMapping("/traffic-surge")
    public Result<List<ApiDegradationVO>> trafficSurge(
            @RequestParam(defaultValue = "day") String compareMode) {
        return Result.success(gatewayService.trafficSurge(compareMode));
    }

    private long parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) return 86400;
        try {
            String lower = timeRange.toLowerCase();
            if (lower.contains("d")) {
                String s = lower.replace("d", "");
                return Long.parseLong(s) * 86400;
            } else if (lower.contains("h")) {
                String s = lower.replace("h", "");
                return Long.parseLong(s) * 3600;
            } else if (lower.contains("m")) {
                String s = lower.replace("m", "");
                return Long.parseLong(s) * 60;
            }
            return Long.parseLong(lower);
        } catch (NumberFormatException e) {
            return 86400;
        }
    }
}
