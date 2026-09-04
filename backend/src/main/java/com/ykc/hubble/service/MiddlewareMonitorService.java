package com.ykc.hubble.service;

import com.aliyuncs.rds.model.v20140815.DescribeDBInstancesResponse;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeInstancesResponse;
import com.ykc.hubble.client.CloudMonitorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 中间件监控服务：Redis + MySQL/PolarDB 实例级监控指标
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiddlewareMonitorService {

    private final CloudMonitorClient cloudMonitorClient;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 查询 Redis 监控概览
     */
    public Map<String, Object> redisOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instances", redisInstances());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 查询 Redis 实例列表 + 每个实例的 CPU/连接数/内存/QPS
     */
    public List<Map<String, Object>> redisInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = cloudMonitorClient.listRedisInstances();
            log.info("Redis 实例数: {}", instances.size());

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5).format(FMT);

            for (var inst : instances) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", inst.getInstanceId());
                item.put("instanceName", inst.getInstanceName());
                item.put("instanceType", inst.getInstanceClass());
                item.put("status", inst.getInstanceStatus());

                String dim = "[{\"instanceId\":\"" + inst.getInstanceId() + "\"}]";

                // CPU 使用率
                double cpu = queryLatestMetric("acs_kvstore", "CpuUsage", dim, startTime, endTime);
                item.put("cpuUsage", cpu);

                // 连接数
                double connections = queryLatestMetric("acs_kvstore", "Connections", dim, startTime, endTime);
                item.put("connections", connections);

                // 内存使用率
                double memoryUsage = queryLatestMetric("acs_kvstore", "MemoryUsage", dim, startTime, endTime);
                item.put("memoryUsage", memoryUsage);

                // QPS
                double qps = queryLatestMetric("acs_kvstore", "QPS", dim, startTime, endTime);
                item.put("qps", qps);

                list.add(item);
            }
        } catch (Exception e) {
            log.error("查询 Redis 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 查询 MySQL/PolarDB 监控概览
     */
    public Map<String, Object> mysqlOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instances", mysqlInstances());
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 查询 RDS/PolarDB 实例列表 + 每个实例的 CPU/连接数/IOPS/慢SQL
     */
    public List<Map<String, Object>> mysqlInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = cloudMonitorClient.listRdsInstances();
            log.info("RDS 实例数: {}", instances.size());

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5).format(FMT);

            for (var inst : instances) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", inst.getDBInstanceId());
                item.put("instanceName", inst.getDBInstanceDescription());
                item.put("engine", inst.getEngine());
                item.put("engineVersion", inst.getEngineVersion());
                item.put("instanceType", inst.getDBInstanceType());
                item.put("status", inst.getDBInstanceStatus());

                String dim = "[{\"instanceId\":\"" + inst.getDBInstanceId() + "\"}]";

                // CPU 使用率
                double cpu = queryLatestMetric("acs_rds_new", "CpuUsage", dim, startTime, endTime);
                item.put("cpuUsage", cpu);

                // 连接数
                double connections = queryLatestMetric("acs_rds_new", "Connections", dim, startTime, endTime);
                item.put("connections", connections);

                // IOPS
                double iops = queryLatestMetric("acs_rds_new", "IOPS", dim, startTime, endTime);
                item.put("iops", iops);

                // 内存使用率
                double memoryUsage = queryLatestMetric("acs_rds_new", "MemoryUsage", dim, startTime, endTime);
                item.put("memoryUsage", memoryUsage);

                list.add(item);
            }
        } catch (Exception e) {
            log.error("查询 MySQL 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 查询最新指标值（取最后一个数据点的 Average）
     */
    private double queryLatestMetric(String namespace, String metric, String dimensions,
                                     String startTime, String endTime) {
        List<double[]> points = cloudMonitorClient.queryMetric(namespace, metric, dimensions, 300, startTime, endTime);
        if (points.isEmpty()) return 0;
        return points.get(points.size() - 1)[1];
    }
}
