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

    @GetMapping("/rocketmq/instances")
    public Result<List<Map<String, Object>>> rocketmqInstances() {
        return Result.success(middlewareMonitorService.rocketmqInstances());
    }

    @GetMapping("/kafka/instances")
    public Result<List<Map<String, Object>>> kafkaInstances() {
        return Result.success(middlewareMonitorService.kafkaInstances());
    }

    @GetMapping("/lindorm/instances")
    public Result<List<Map<String, Object>>> lindormInstances() {
        return Result.success(middlewareMonitorService.lindormInstances());
    }

    @GetMapping("/elasticsearch/instances")
    public Result<List<Map<String, Object>>> elasticsearchInstances() {
        return Result.success(middlewareMonitorService.elasticsearchInstances());
    }

    @GetMapping("/oss/buckets")
    public Result<List<Map<String, Object>>> ossBuckets() {
        return Result.success(middlewareMonitorService.ossBuckets());
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

    @GetMapping("/rocketmq/top-topics")
    public Result<List<Map<String, Object>>> rocketmqTopTopics() {
        return Result.success(middlewareMonitorService.rocketmqTopTopics());
    }

    @GetMapping("/kafka/top-partitions")
    public Result<List<Map<String, Object>>> kafkaTopPartitions() {
        return Result.success(middlewareMonitorService.kafkaTopPartitions());
    }

    @GetMapping("/redis/big-keys")
    public Result<List<Map<String, Object>>> redisBigKeys() {
        return Result.success(middlewareMonitorService.redisBigKeys());
    }

    @GetMapping("/redis/slow-queries")
    public Result<List<Map<String, Object>>> redisSlowQueries() {
        return Result.success(middlewareMonitorService.redisSlowQueries());
    }

    @GetMapping("/mysql/top-tables")
    public Result<List<Map<String, Object>>> mysqlTopTables() {
        return Result.success(middlewareMonitorService.mysqlTopTables());
    }

    @GetMapping("/mysql/slow-queries")
    public Result<List<Map<String, Object>>> mysqlSlowQueries() {
        return Result.success(middlewareMonitorService.mysqlSlowQueries());
    }

    @GetMapping("/lindorm/top-tables")
    public Result<List<Map<String, Object>>> lindormTopTables() {
        return Result.success(middlewareMonitorService.lindormTopTables());
    }

    @GetMapping("/elasticsearch/top-indices")
    public Result<List<Map<String, Object>>> elasticsearchTopIndices() {
        return Result.success(middlewareMonitorService.elasticsearchTopIndices());
    }
}
