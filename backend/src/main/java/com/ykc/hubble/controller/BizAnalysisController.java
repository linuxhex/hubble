package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.common.exception.BusinessException;
import com.ykc.hubble.config.BizAnalysisProperties;
import com.ykc.hubble.service.BizAnalysisService;
import com.ykc.hubble.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/biz-analysis")
@RequiredArgsConstructor
@Tag(name = "经营分析", description = "经营分析数据（仅 lianzi 可见）")
public class BizAnalysisController {

    private final BizAnalysisService bizAnalysisService;
    private final BizAnalysisProperties properties;
    private final JwtUtil jwtUtil;

    @GetMapping("/overview")
    @Operation(summary = "汇总卡片")
    public Result<Map<String, Object>> overview(HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.dailyOverview());
    }

    @GetMapping("/monthly-trend")
    @Operation(summary = "月度趋势+环比")
    public Result<List<Map<String, Object>>> monthlyTrend(HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.monthlyTrend());
    }

    @GetMapping("/daily")
    @Operation(summary = "每日订单电量")
    public Result<List<Map<String, Object>>> daily(
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.dailyOrderEnergy(days));
    }

    @GetMapping("/scenario")
    @Operation(summary = "业务场景拆分")
    public Result<Map<String, Object>> scenario(HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.scenarioBreakdown());
    }

    @GetMapping("/active-users")
    @Operation(summary = "充电最活跃用户排名")
    public Result<List<Map<String, Object>>> activeUsers(
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.activeUsersTop(limit));
    }

    @GetMapping("/app-active")
    @Operation(summary = "小程序活跃数据")
    public Result<List<Map<String, Object>>> appActive(
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.appActive(days));
    }

    @GetMapping("/yearly-comparison")
    @Operation(summary = "年度同比对比")
    public Result<Map<String, Object>> yearlyComparison(HttpServletRequest request) {
        checkPermission(request);
        return Result.success(bizAnalysisService.yearlyComparison());
    }

    private void checkPermission(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            throw new BusinessException(401, "未登录");
        }
        String username = jwtUtil.getUsername(token);
        if (username == null || !properties.getAllowedUsers().contains(username)) {
            throw new BusinessException(403, "无权限访问经营分析");
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return request.getParameter("token");
    }
}
