package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.entity.MiddlewareAlertConfig;
import com.ykc.hubble.service.MiddlewareAlertService;
import com.ykc.hubble.service.MiddlewareMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/middleware-alert")
@RequiredArgsConstructor
@Tag(name = "中间件告警配置", description = "中间件监控告警阈值配置与检查")
public class MiddlewareAlertController {

    private final MiddlewareAlertService alertService;
    private final MiddlewareMonitorService monitorService;

    @GetMapping("/config/list")
    @Operation(summary = "查询告警配置列表")
    public Result<List<MiddlewareAlertConfig>> list(
            @RequestParam(required = false) String middlewareType) {
        return Result.success(alertService.listByType(middlewareType));
    }

    @GetMapping("/config/{id}")
    @Operation(summary = "查询告警配置详情")
    public Result<MiddlewareAlertConfig> detail(@PathVariable Long id) {
        var config = alertService.detail(id);
        if (config == null) return Result.notFound("配置不存在");
        return Result.success(config);
    }

    @PostMapping("/config")
    @Operation(summary = "新增告警配置")
    public Result<Void> create(@RequestBody MiddlewareAlertConfig config) {
        alertService.create(config);
        return Result.success();
    }

    @PutMapping("/config/{id}")
    @Operation(summary = "更新告警配置")
    public Result<Void> update(@PathVariable Long id, @RequestBody MiddlewareAlertConfig config) {
        config.setId(id);
        alertService.update(config);
        return Result.success();
    }

    @DeleteMapping("/config/{id}")
    @Operation(summary = "删除告警配置")
    public Result<Void> delete(@PathVariable Long id) {
        alertService.delete(id);
        return Result.success();
    }

    @GetMapping("/check/{middlewareType}")
    @Operation(summary = "检查中间件告警状态")
    public Result<Map<String, Object>> checkAlerts(@PathVariable String middlewareType) {
        List<Map<String, Object>> instances = fetchInstances(middlewareType);
        if (instances == null) {
            return Result.badRequest("不支持的中间件类型: " + middlewareType);
        }

        List<Map<String, Object>> checked = alertService.checkAlerts(middlewareType, instances);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("middlewareType", middlewareType);
        result.put("summary", alertService.alertSummary(checked));
        result.put("instances", checked);
        return Result.success(result);
    }

    @GetMapping("/check/all")
    @Operation(summary = "检查所有中间件告警状态")
    public Result<Map<String, Object>> checkAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] types = {"redis", "mysql", "rocketmq", "kafka", "lindorm", "elasticsearch", "oss"};

        int totalRed = 0, totalYellow = 0;
        for (String type : types) {
            List<Map<String, Object>> instances = fetchInstances(type);
            if (instances == null) continue;
            List<Map<String, Object>> checked = alertService.checkAlerts(type, instances);
            Map<String, Object> summary = alertService.alertSummary(checked);
            result.put(type, summary);
            totalRed += (int) summary.getOrDefault("redCount", 0);
            totalYellow += (int) summary.getOrDefault("yellowCount", 0);
        }

        result.put("totalRed", totalRed);
        result.put("totalYellow", totalYellow);
        return Result.success(result);
    }

    private List<Map<String, Object>> fetchInstances(String middlewareType) {
        return switch (middlewareType) {
            case "redis" -> new ArrayList<>(monitorService.redisInstances());
            case "mysql" -> new ArrayList<>(monitorService.mysqlInstances());
            case "rocketmq" -> new ArrayList<>(monitorService.rocketmqInstances());
            case "kafka" -> new ArrayList<>(monitorService.kafkaInstances());
            case "lindorm" -> new ArrayList<>(monitorService.lindormInstances());
            case "elasticsearch" -> new ArrayList<>(monitorService.elasticsearchInstances());
            case "oss" -> new ArrayList<>(monitorService.ossBuckets());
            default -> null;
        };
    }
}
