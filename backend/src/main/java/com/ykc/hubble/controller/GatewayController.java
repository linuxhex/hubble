package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.GatewayService;
import com.ykc.hubble.vo.ApiDegradationVO;
import com.ykc.hubble.vo.GatewayHotApiVO;
import com.ykc.hubble.vo.GatewayOverviewVO;
import com.ykc.hubble.vo.GatewayTrendVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    @GetMapping("/overview")
    public Result<GatewayOverviewVO> overview(
            @RequestParam(defaultValue = "24h") String timeRange) {
        return Result.success(gatewayService.overview(timeRange));
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
}
