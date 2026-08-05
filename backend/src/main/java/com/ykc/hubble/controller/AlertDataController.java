package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.AlertDataService;
import com.ykc.hubble.service.AlertPushService;
import com.ykc.hubble.service.ServiceLogService;
import com.ykc.hubble.vo.AlertStatisticVO;
import com.ykc.hubble.vo.ServiceLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 监控数据控制器（对齐前端 alert.js 的 alert-data 契约 + 下钻 + SSE 推送）
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/alert-data")
@RequiredArgsConstructor
@Tag(name = "日志监控数据服务", description = "实时统计、时序明细、下钻与推送")
public class AlertDataController {

    private final AlertDataService alertDataService;
    private final ServiceLogService serviceLogService;
    private final AlertPushService alertPushService;

    @GetMapping("/query")
    @Operation(summary = "分页查询时序明细", description = "返回指定时间范围的采集点序列")
    public Result<Map<String, Object>> query(
            @RequestParam Long alertConfigId,
            @RequestParam(defaultValue = "15m") String timeRange,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "100") int size) {
        return Result.success(alertDataService.query(alertConfigId, timeRange, current, size));
    }

    @GetMapping("/query/statistics/{configId}")
    @Operation(summary = "获取统计数据", description = "当前值/今日峰值/均值/告警计数/健康度")
    public Result<AlertStatisticVO> statistics(
            @PathVariable Long configId,
            @RequestParam(defaultValue = "15m") String timeRange) {
        return Result.success(alertDataService.statistics(configId, timeRange));
    }

    @GetMapping("/service-logs")
    @Operation(summary = "下钻：查监控项对应服务的实时日志", description = "按 configId 取模板，在 all 库拉日志")
    public Result<List<ServiceLogVO>> serviceLogs(
            @RequestParam Long configId,
            @RequestParam(defaultValue = "15m") String timeRange,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(serviceLogService.serviceLogs(configId, timeRange, limit));
    }

    @GetMapping("/trace-logs")
    @Operation(summary = "下钻：按 traceId 查跨服务日志", description = "在 all 库查 trace:<traceId>，轻量串联同一请求穿过的服务")
    public Result<List<ServiceLogVO>> traceLogs(
            @RequestParam String traceId,
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(defaultValue = "200") int limit) {
        return Result.success(serviceLogService.traceLogs(traceId, timeRange, limit));
    }

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅实时告警（SSE）", description = "采集到红盘时通过该通道推送，独立于轮询")
    public SseEmitter subscribe() {
        return alertPushService.subscribe();
    }

    @GetMapping("/service-health")
    @Operation(summary = "按服务维度查看健康状态", description = "返回每个服务的错误数、阈值、红黄绿状态")
    public Result<List<Map<String, Object>>> serviceHealth(
            @RequestParam(defaultValue = "15m") String timeRange) {
        return Result.success(alertDataService.serviceHealth(timeRange));
    }

    @GetMapping("/minute-timeline")
    @Operation(summary = "分钟级健康时间线", description = "每分钟取最差服务状态，返回红/黄/绿时间线")
    public Result<Map<String, Object>> minuteTimeline(
            @RequestParam(defaultValue = "15m") String timeRange) {
        return Result.success(alertDataService.minuteHealthTimeline(timeRange));
    }

    @GetMapping("/service-drilldown")
    @Operation(summary = "服务下钻", description = "查看服务错误日志、异常分类、下游依赖")
    public Result<Map<String, Object>> serviceDrillDown(
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "15m") String timeRange) {
        return Result.success(alertDataService.serviceDrillDown(serviceName, timeRange));
    }
}
