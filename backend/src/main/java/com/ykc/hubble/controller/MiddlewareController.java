package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.MiddlewareMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 中间件监控：Redis + MySQL/PolarDB
 */
@RestController
@RequestMapping("/middleware")
@RequiredArgsConstructor
public class MiddlewareController {

    private final MiddlewareMonitorService middlewareMonitorService;

    @GetMapping("/redis")
    public Result<Map<String, Object>> redisOverview() {
        return Result.success(middlewareMonitorService.redisOverview());
    }

    @GetMapping("/redis/instances")
    public Result<List<Map<String, Object>>> redisInstances() {
        return Result.success(middlewareMonitorService.redisInstances());
    }

    @GetMapping("/mysql")
    public Result<Map<String, Object>> mysqlOverview() {
        return Result.success(middlewareMonitorService.mysqlOverview());
    }

    @GetMapping("/mysql/instances")
    public Result<List<Map<String, Object>>> mysqlInstances() {
        return Result.success(middlewareMonitorService.mysqlInstances());
    }

    @GetMapping("/pod/cpu")
    public Result<List<Map<String, Object>>> podCpuTop() {
        return Result.success(middlewareMonitorService.podCpuTop());
    }

    @GetMapping("/pod/memory")
    public Result<List<Map<String, Object>>> podMemoryTop() {
        return Result.success(middlewareMonitorService.podMemoryTop());
    }

    @GetMapping("/node/overview")
    public Result<List<Map<String, Object>>> nodeOverview() {
        return Result.success(middlewareMonitorService.nodeOverview());
    }
}
