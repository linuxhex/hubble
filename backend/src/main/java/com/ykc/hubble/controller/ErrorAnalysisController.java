package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.ErrorAnalysisService;
import com.ykc.hubble.vo.ErrorTrendVO;
import com.ykc.hubble.vo.ErrorTypeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 错误分析控制器（对齐前端 errorAnalysis.js 契约）
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/error-analysis")
@RequiredArgsConstructor
@Tag(name = "错误分析服务", description = "错误类型排行与趋势")
public class ErrorAnalysisController {

    private final ErrorAnalysisService errorAnalysisService;

    @GetMapping("/query/direct-errors")
    @Operation(summary = "直接查询错误", description = "不依赖告警配置，直接查询 SLS ERROR 日志")
    public Result<List<ErrorTypeVO>> directErrors(
            @RequestParam(defaultValue = "30m") String timeRange,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(errorAnalysisService.queryDirectErrors(timeRange, limit));
    }

    @GetMapping("/query/error-types/{alertConfigId}")
    @Operation(summary = "错误类型排行", description = "Top N 错误类型 + 占比 + 分类 + 样本")
    public Result<List<ErrorTypeVO>> topErrorTypes(
            @PathVariable Long alertConfigId,
            @RequestParam(defaultValue = "30m") String timeRange,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(errorAnalysisService.topErrorTypes(alertConfigId, timeRange, limit));
    }

    @GetMapping("/query/error-types-trend/{alertConfigId}")
    @Operation(summary = "Top错误类型趋势")
    public Result<ErrorTrendVO> topErrorTypesTrend(
            @PathVariable Long alertConfigId,
            @RequestParam(defaultValue = "30m") String timeRange,
            @RequestParam(defaultValue = "5m") String interval) {
        return Result.success(errorAnalysisService.topErrorTypesTrend(alertConfigId, timeRange, interval));
    }

    @GetMapping("/query/error-trend/{alertConfigId}")
    @Operation(summary = "错误数趋势")
    public Result<Map<String, Object>> errorTrend(
            @PathVariable Long alertConfigId,
            @RequestParam(defaultValue = "1d") String timeRange,
            @RequestParam(defaultValue = "1h") String interval,
            @RequestParam(required = false) String typeId) {
        return Result.success(errorAnalysisService.errorTrend(alertConfigId, timeRange));
    }
}
