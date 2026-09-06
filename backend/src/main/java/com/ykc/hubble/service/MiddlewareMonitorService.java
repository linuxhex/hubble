package com.ykc.hubble.service;

import com.aliyuncs.rds.model.v20140815.DescribeDBInstancesResponse;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeInstancesResponse;
import com.ykc.hubble.client.CloudMonitorClient;
import com.ykc.hubble.client.GrafanaClient;
import com.ykc.hubble.config.MiddlewareProperties;
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
    private final GrafanaClient grafanaClient;
    private final MiddlewareProperties middlewareProperties;
    private final PageDataCacheService pageDataCacheService;
    private final com.ykc.hubble.client.SlsQueryClient slsQueryClient;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final java.util.concurrent.Executor queryExecutor;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Grafana 查询缓存（避免频繁查询，2 分钟过期）
    private List<Map<String, Object>> podCpuCache = null;
    private long podCpuCacheTime = 0;
    private List<Map<String, Object>> podMemCache = null;
    private long podMemCacheTime = 0;
    private List<Map<String, Object>> nodeCache = null;
    private long nodeCacheTime = 0;
    private static final long CACHE_TTL_MS = 2 * 60 * 1000; // 2 分钟

    // Redis/MySQL 监控缓存（5 分钟过期，避免频繁调用 CloudMonitor）
    private List<Map<String, Object>> redisInstancesCache = null;
    private long redisInstancesCacheTime = 0;
    private List<Map<String, Object>> mysqlInstancesCache = null;
    private long mysqlInstancesCacheTime = 0;
    private static final long MONITOR_CACHE_TTL_MS = 5 * 60 * 1000; // 5 分钟

    private List<Map<String, Object>> rocketmqInstancesCache = null;
    private long rocketmqInstancesCacheTime = 0;
    private List<Map<String, Object>> kafkaInstancesCache = null;
    private long kafkaInstancesCacheTime = 0;
    private List<Map<String, Object>> lindormInstancesCache = null;
    private long lindormInstancesCacheTime = 0;
    private List<Map<String, Object>> elasticsearchInstancesCache = null;
    private long elasticsearchInstancesCacheTime = 0;
    private List<Map<String, Object>> ossBucketsCache = null;
    private long ossBucketsCacheTime = 0;

    // Top 指标缓存
    private List<Map<String, Object>> rocketmqTopTopicsCache = null;
    private long rocketmqTopTopicsCacheTime = 0;
    private List<Map<String, Object>> kafkaTopPartitionsCache = null;
    private long kafkaTopPartitionsCacheTime = 0;
    private List<Map<String, Object>> redisBigKeysCache = null;
    private long redisBigKeysCacheTime = 0;
    private List<Map<String, Object>> redisSlowQueriesCache = null;
    private long redisSlowQueriesCacheTime = 0;
    private List<Map<String, Object>> mysqlSlowQueriesCache = null;
    private long mysqlSlowQueriesCacheTime = 0;
    private List<Map<String, Object>> mysqlTopTablesCache = null;
    private long mysqlTopTablesCacheTime = 0;
    private List<Map<String, Object>> lindormTopTablesCache = null;
    private long lindormTopTablesCacheTime = 0;
    private List<Map<String, Object>> elasticsearchTopIndicesCache = null;
    private long elasticsearchTopIndicesCacheTime = 0;

    // DB 分库 / Druid 连接池 / JVM 监控缓存（5 分钟）
    private List<Map<String, Object>> dbInstancesCache = null;
    private long dbInstancesCacheTime = 0;
    private List<Map<String, Object>> druidInstancesCache = null;
    private long druidInstancesCacheTime = 0;
    private List<Map<String, Object>> jvmInstancesCache = null;
    private long jvmInstancesCacheTime = 0;

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
     * 查询 Redis 实例列表 + 每个实例的 CPU/连接数/内存/QPS（仅 prod 环境）
     * 先返回 DB 缓存（快速展示），后台异步刷新最新数据覆盖缓存。
     */
    public List<Map<String, Object>> redisInstances() {
        long now = System.currentTimeMillis();
        if (redisInstancesCache != null && now - redisInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            log.debug("Redis 实例使用内存缓存（{} 个）", redisInstancesCache.size());
            return redisInstancesCache;
        }

        // DB 缓存：先返回，后台异步刷新
        List<Map<String, Object>> dbCached = loadFromDbCache("middleware_redis", "instances");
        if (dbCached != null && !dbCached.isEmpty()) {
            redisInstancesCache = dbCached;
            redisInstancesCacheTime = now;
            java.util.concurrent.CompletableFuture.runAsync(this::refreshRedisInstances, queryExecutor);
            log.debug("Redis 实例使用 DB 缓存（{} 个），后台刷新中", dbCached.size());
            return dbCached;
        }

        return refreshRedisInstances();
    }

    private List<Map<String, Object>> refreshRedisInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var allInstances = cloudMonitorClient.listRedisInstances();
            var instances = allInstances.stream()
                    .filter(inst -> inst.getInstanceName() != null && inst.getInstanceName().startsWith("prod-"))
                    .toList();
            log.info("Redis 实例数: 总{} 个, prod {} 个", allInstances.size(), instances.size());

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            var futures = instances.stream().map(inst ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", inst.getInstanceId());
                    item.put("instanceName", inst.getInstanceName());
                    item.put("instanceType", inst.getInstanceClass());
                    item.put("status", inst.getInstanceStatus());

                    String dim = "[{\"instanceId\":\"" + inst.getInstanceId() + "\"}]";

                    item.put("cpuUsage", queryLatestMetric("acs_kvstore", "CpuUsage", dim, startTime, endTime));
                    item.put("connections", queryLatestMetric("acs_kvstore", "ConnectionUsage", dim, startTime, endTime));
                    item.put("memoryUsage", queryLatestMetric("acs_kvstore", "MemoryUsage", dim, startTime, endTime));
                    item.put("qps", queryLatestMetric("acs_kvstore", "ShardingCommandQPS", dim, startTime, endTime));
                    return item;
                })
            ).toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(futures).join();
            for (var f : futures) {
                list.add((Map<String, Object>) f.get());
            }

            redisInstancesCache = list;
            redisInstancesCacheTime = System.currentTimeMillis();
            if (!list.isEmpty()) {
                saveToDbCache("middleware_redis", "instances", list);
            }
            log.info("Redis prod 实例监控已缓存: {} 个", list.size());
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
     * 查询 RDS/PolarDB 实例列表 + 每个实例的 CPU/连接数/IOPS/磁盘（仅 prod 环境，带 5 分钟缓存）
     */
    public List<Map<String, Object>> mysqlInstances() {
        long now = System.currentTimeMillis();
        if (mysqlInstancesCache != null && now - mysqlInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            log.debug("MySQL 实例使用缓存（{} 个）", mysqlInstancesCache.size());
            return mysqlInstancesCache;
        }

        List<Map<String, Object>> dbCached = loadFromDbCache("middleware_mysql", "instances");
        if (dbCached != null && !dbCached.isEmpty()) {
            mysqlInstancesCache = dbCached;
            mysqlInstancesCacheTime = now;
            java.util.concurrent.CompletableFuture.runAsync(this::refreshMysqlInstances, queryExecutor);
            log.debug("MySQL 实例使用 DB 缓存（{} 个），后台刷新中", dbCached.size());
            return dbCached;
        }

        return refreshMysqlInstances();
    }

    private List<Map<String, Object>> refreshMysqlInstances() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var allInstances = cloudMonitorClient.listRdsInstances();
            var instances = allInstances.stream()
                    .filter(inst -> inst.getDBInstanceDescription() != null && inst.getDBInstanceDescription().startsWith("prod-"))
                    .toList();
            log.info("RDS 实例数: 总{} 个, prod {} 个", allInstances.size(), instances.size());

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            var futures = instances.stream().map(inst ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", inst.getDBInstanceId());
                    item.put("instanceName", inst.getDBInstanceDescription());
                    item.put("engine", inst.getEngine());
                    item.put("engineVersion", inst.getEngineVersion());
                    item.put("instanceType", inst.getDBInstanceType());
                    item.put("status", inst.getDBInstanceStatus());

                    String dim = "[{\"instanceId\":\"" + inst.getDBInstanceId() + "\"}]";

                    item.put("cpuUsage", queryLatestMetric("acs_rds_dashboard", "CpuUsage", dim, startTime, endTime));
                    item.put("connections", queryLatestMetric("acs_rds_dashboard", "ConnectionUsage", dim, startTime, endTime));
                    item.put("iops", queryLatestMetric("acs_rds_dashboard", "IOPSUsage", dim, startTime, endTime));
                    item.put("diskUsage", queryLatestMetric("acs_rds_dashboard", "DiskUsage", dim, startTime, endTime));
                    return item;
                })
            ).toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(futures).join();
            for (var f : futures) {
                list.add((Map<String, Object>) f.get());
            }

            mysqlInstancesCache = list;
            mysqlInstancesCacheTime = now;
            if (!list.isEmpty()) {
                saveToDbCache("middleware_mysql", "instances", list);
            }
            log.info("MySQL prod 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 MySQL 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 查询 Pod CPU Top10（从 Grafana/Prometheus），带 2 分钟缓存
     */
    public List<Map<String, Object>> podCpuTop() {
        long now = System.currentTimeMillis();
        if (podCpuCache != null && now - podCpuCacheTime < CACHE_TTL_MS) {
            log.debug("Pod CPU 使用缓存（{} 条）", podCpuCache.size());
            return podCpuCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var results = grafanaClient.queryInstant(
                    "topk(10, sum(rate(container_cpu_usage_seconds_total{container!=\"\",container!=\"POD\"}[5m])) by (pod))");
            for (var item : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pod", item.getOrDefault("pod", "unknown"));
                row.put("cpu", item.getOrDefault("value", 0));
                list.add(row);
            }
            podCpuCache = list;
            podCpuCacheTime = now;
            log.info("Pod CPU Top: {} 个（已缓存）", list.size());
        } catch (Exception e) {
            log.error("查询 Pod CPU 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 查询 Pod 内存 Top10（从 Grafana/Prometheus），带 2 分钟缓存
     */
    public List<Map<String, Object>> podMemoryTop() {
        long now = System.currentTimeMillis();
        if (podMemCache != null && now - podMemCacheTime < CACHE_TTL_MS) {
            log.debug("Pod Memory 使用缓存（{} 条）", podMemCache.size());
            return podMemCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var results = grafanaClient.queryInstant(
                    "topk(10, sum(container_memory_working_set_bytes{container!=\"\",container!=\"POD\"}) by (pod))");
            for (var item : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pod", item.getOrDefault("pod", "unknown"));
                double bytes = toDouble(item.getOrDefault("value", 0));
                row.put("memoryMB", Math.round(bytes / 1024 / 1024 * 10) / 10.0);
                list.add(row);
            }
            podMemCache = list;
            podMemCacheTime = now;
            log.info("Pod Memory Top: {} 个（已缓存）", list.size());
        } catch (Exception e) {
            log.error("查询 Pod 内存失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 查询 Node 节点资源使用率（CPU + 内存），带 2 分钟缓存
     */
    public List<Map<String, Object>> nodeOverview() {
        long now = System.currentTimeMillis();
        if (nodeCache != null && now - nodeCacheTime < CACHE_TTL_MS) {
            log.debug("Node 使用缓存（{} 条）", nodeCache.size());
            return nodeCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String nodeDs = grafanaClient.getNodeDsUid();
            // Node CPU
            var cpuResults = grafanaClient.queryInstant(
                    "100 * (1 - avg by (instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[5m])))", nodeDs);
            // Node Memory
            var memResults = grafanaClient.queryInstant(
                    "(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100", nodeDs);

            Map<String, Double> cpuMap = new HashMap<>();
            for (var item : cpuResults) {
                String node = String.valueOf(item.getOrDefault("instance", "unknown"));
                double cpu = toDouble(item.getOrDefault("value", 0));
                cpuMap.put(node, cpu);
            }

            Map<String, double[]> nodeMap = new LinkedHashMap<>();
            for (var item : memResults) {
                String node = String.valueOf(item.getOrDefault("instance", "unknown"));
                double mem = toDouble(item.getOrDefault("value", 0));
                nodeMap.put(node, new double[]{cpuMap.getOrDefault(node, 0.0), mem});
            }

            for (var entry : nodeMap.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("node", entry.getKey());
                row.put("cpuUsage", entry.getValue()[0]);
                row.put("memoryUsage", entry.getValue()[1]);
                list.add(row);
            }
            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("cpuUsage", 0)),
                    toDouble(a.getOrDefault("cpuUsage", 0))));
            nodeCache = list;
            nodeCacheTime = now;
            log.info("Node 概览: {} 个节点（已缓存）", list.size());
        } catch (Exception e) {
            log.error("查询 Node 概览失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * RocketMQ 实例监控：从 SLS 日志统计消息量（CloudMonitor/Prometheus 均无 RocketMQ 实例级指标）。
     * 按 topic 聚合近 15 分钟的发送/消费消息数，5 分钟缓存。
     */
    public List<Map<String, Object>> rocketmqInstances() {
        long now = System.currentTimeMillis();
        if (rocketmqInstancesCache != null && now - rocketmqInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return rocketmqInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String logstore = "all";
            long nowSec = now / 1000;
            long fromSec = nowSec - 900; // 近 15 分钟

            // 统计发送消息量（按 topic 聚合）
            long sendCount = slsQueryClient.countLogstore(logstore, "发送消息到tp_成功 OR 发送消息成功", fromSec, nowSec);
            // 统计消费消息量
            long consumeCount = slsQueryClient.countLogstore(logstore, "收到*消息*topic", fromSec, nowSec);
            // 统计消息堆积/异常
            long accumulationCount = slsQueryClient.countLogstore(logstore, "消息堆积 OR 消费失败 OR consumeFailed", fromSec, nowSec);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", "rocketmq-prod");
            item.put("instanceName", "RocketMQ 生产集群");
            item.put("messageAccumulation", accumulationCount);
            item.put("sendTps", sendCount / 900.0); // 近 15 分钟平均 TPS
            item.put("consumeTps", consumeCount / 900.0);
            list.add(item);

            rocketmqInstancesCache = list;
            rocketmqInstancesCacheTime = now;
            log.info("RocketMQ 监控(SLS): 发送={}, 消费={}, 堆积={}", sendCount, consumeCount, accumulationCount);
        } catch (Exception e) {
            log.error("查询 RocketMQ 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Kafka 实例监控（消息堆积/生产TPS/消费TPS），5 分钟缓存。
     * 优先从 Aliyun Prometheus 获取（CloudMonitor API 返回 0 实例），CloudMonitor 作 fallback。
     */
    public List<Map<String, Object>> kafkaInstances() {
        long now = System.currentTimeMillis();
        if (kafkaInstancesCache != null && now - kafkaInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return kafkaInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            // 优先：Aliyun Prometheus 发现实例
            String aliyunDs = grafanaClient.getAliyunDsUid();
            var instanceRows = grafanaClient.queryInstant(
                    "count by (instanceId) ({__name__=~\"AliyunKafka_.*\"})", aliyunDs);

            if (!instanceRows.isEmpty()) {
                for (var row : instanceRows) {
                    String instanceId = String.valueOf(row.getOrDefault("instanceId", ""));
                    if (instanceId.isEmpty()) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", instanceId);
                    item.put("instanceName", instanceId);

                    // 消息堆积
                    var lagResults = grafanaClient.queryInstant(
                            "sum by (instanceId) (AliyunKafka_message_accumulation{instanceId=\"" + instanceId + "\"})", aliyunDs);
                    item.put("lag", extractValue(lagResults));

                    // 生产 TPS
                    var produceResults = grafanaClient.queryInstant(
                            "sum by (instanceId) (AliyunKafka_instance_message_input{instanceId=\"" + instanceId + "\"})", aliyunDs);
                    item.put("produceTps", extractValue(produceResults));

                    // 消费 TPS
                    var consumeResults = grafanaClient.queryInstant(
                            "sum by (instanceId) (AliyunKafka_instance_message_output{instanceId=\"" + instanceId + "\"})", aliyunDs);
                    item.put("consumeTps", extractValue(consumeResults));

                    list.add(item);
                }
                log.info("Kafka 实例监控(Prometheus): {} 个", list.size());
            } else {
                // Fallback: CloudMonitor API
                var allInstances = cloudMonitorClient.listKafkaInstances();
                var instances = allInstances.stream()
                        .filter(inst -> {
                            String name = (String) inst.get("name");
                            return name != null && name.startsWith("prod-");
                        })
                        .toList();
                log.info("Kafka 实例数(CloudMonitor fallback): 总{} 个, prod {} 个", allInstances.size(), instances.size());

                String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

                for (var inst : instances) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String instanceId = (String) inst.get("instanceId");
                    String name = (String) inst.get("name");
                    item.put("instanceId", instanceId);
                    item.put("instanceName", name);

                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    item.put("lag", queryLatestMetric("acs_kafka", "Lag", dim, startTime, endTime));
                    item.put("produceTps", queryLatestMetric("acs_kafka", "ProduceTps", dim, startTime, endTime));
                    item.put("consumeTps", queryLatestMetric("acs_kafka", "ConsumeTps", dim, startTime, endTime));
                    list.add(item);
                }
            }

            kafkaInstancesCache = list;
            kafkaInstancesCacheTime = now;
            log.info("Kafka 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 Kafka 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Lindorm 实例监控（CPU/读QPS/写QPS），5 分钟缓存。
     * 优先从 Aliyun Prometheus 获取（CloudMonitor API 返回 0 实例），CloudMonitor 作 fallback。
     */
    public List<Map<String, Object>> lindormInstances() {
        long now = System.currentTimeMillis();
        if (lindormInstancesCache != null && now - lindormInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return lindormInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            // 优先：Aliyun Prometheus 发现实例 + 查指标
            String aliyunDs = grafanaClient.getAliyunDsUid();
            var instanceRows = grafanaClient.queryInstant(
                    "count by (instanceId) (AliyunLindorm_cpu_user)", aliyunDs);

            if (!instanceRows.isEmpty()) {
                for (var row : instanceRows) {
                    String instanceId = String.valueOf(row.getOrDefault("instanceId", ""));
                    if (instanceId.isEmpty()) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", instanceId);
                    item.put("instanceName", "lindorm-" + instanceId);

                    // CPU：取所有 host 的平均值
                    var cpuResults = grafanaClient.queryInstant(
                            "avg by (instanceId) (AliyunLindorm_cpu_user{instanceId=\"" + instanceId + "\",host=~\"lindormtable-.*\"})", aliyunDs);
                    item.put("cpuUsage", extractValue(cpuResults));

                    // 读 QPS
                    var readResults = grafanaClient.queryInstant(
                            "sum by (instanceId) (AliyunLindorm_read_ops{instanceId=\"" + instanceId + "\",host=~\"lindormtable-.*\"})", aliyunDs);
                    item.put("qps", extractValue(readResults));

                    // 写 QPS
                    var writeResults = grafanaClient.queryInstant(
                            "sum by (instanceId) (AliyunLindorm_write_ops{instanceId=\"" + instanceId + "\",host=~\"lindormtable-.*\"})", aliyunDs);
                    item.put("writeQps", extractValue(writeResults));

                    // 磁盘使用率（CloudMonitor 补）
                    String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                    String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);
                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    item.put("diskUsage", queryLatestMetric("acs_lindorm", "DiskUsage", dim, startTime, endTime));

                    list.add(item);
                }
                log.info("Lindorm 实例监控(Prometheus): {} 个", list.size());
            } else {
                // Fallback: CloudMonitor API
                var allInstances = cloudMonitorClient.listLindormInstances();
                var instances = allInstances.stream()
                        .filter(inst -> {
                            String alias = (String) inst.get("instanceAlias");
                            return alias != null && alias.startsWith("prod-");
                        })
                        .toList();
                log.info("Lindorm 实例数(CloudMonitor fallback): 总{} 个, prod {} 个", allInstances.size(), instances.size());

                String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

                for (var inst : instances) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String instanceId = (String) inst.get("instanceId");
                    String alias = (String) inst.get("instanceAlias");
                    item.put("instanceId", instanceId);
                    item.put("instanceName", alias);

                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    item.put("cpuUsage", queryLatestMetric("acs_lindorm", "CpuUsage", dim, startTime, endTime));
                    item.put("diskUsage", queryLatestMetric("acs_lindorm", "DiskUsage", dim, startTime, endTime));
                    item.put("qps", queryLatestMetric("acs_lindorm", "Qps", dim, startTime, endTime));
                    list.add(item);
                }
            }

            lindormInstancesCache = list;
            lindormInstancesCacheTime = now;
            log.info("Lindorm 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 Lindorm 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Elasticsearch 实例监控（CPU/磁盘/JVM内存），5 分钟缓存，自动发现实例
     */
    public List<Map<String, Object>> elasticsearchInstances() {
        long now = System.currentTimeMillis();
        if (elasticsearchInstancesCache != null && now - elasticsearchInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return elasticsearchInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var allInstances = cloudMonitorClient.listElasticsearchInstances();
            var instances = allInstances.stream()
                    .filter(inst -> {
                        String desc = (String) inst.get("description");
                        return desc != null && desc.startsWith("prod-");
                    })
                    .toList();
            log.info("Elasticsearch 实例数: 总{} 个, prod {} 个", allInstances.size(), instances.size());

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var inst : instances) {
                Map<String, Object> item = new LinkedHashMap<>();
                String instanceId = (String) inst.get("instanceId");
                String desc = (String) inst.get("description");
                item.put("instanceId", instanceId);
                item.put("instanceName", desc);

                String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                item.put("cpuUsage", queryLatestMetric("acs_elasticsearch", "NodeCPUUtilization", dim, startTime, endTime));
                item.put("diskUsage", queryLatestMetric("acs_elasticsearch", "NodeDiskUtilization", dim, startTime, endTime));
                item.put("jvmMemory", queryLatestMetric("acs_elasticsearch", "NodeJVMMemoryUsedPercent", dim, startTime, endTime));
                list.add(item);
            }

            elasticsearchInstancesCache = list;
            elasticsearchInstancesCacheTime = now;
            log.info("Elasticsearch 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 Elasticsearch 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * OSS Bucket 监控（请求数/带宽/错误率），5 分钟缓存，自动发现 Bucket
     */
    public List<Map<String, Object>> ossBuckets() {
        long now = System.currentTimeMillis();
        if (ossBucketsCache != null && now - ossBucketsCacheTime < MONITOR_CACHE_TTL_MS) {
            return ossBucketsCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var allBuckets = cloudMonitorClient.listOSSBuckets();
            var buckets = allBuckets.stream()
                    .filter(bucket -> bucket.getName() != null)
                    .toList();
            log.info("OSS Bucket 数: 总{} 个", allBuckets.size());

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var bucket : buckets) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bucketName", bucket.getName());
                item.put("instanceName", bucket.getName());
                item.put("location", bucket.getLocation());
                item.put("creationDate", String.valueOf(bucket.getCreationDate()));

                String dim = "[{\"BucketName\":\"" + bucket.getName() + "\"}]";
                // 尝试多个指标，能取到什么就展示什么
                double totalReq = queryLatestMetric("acs_oss", "TotalRequestCount", dim, startTime, endTime);
                double internetSend = queryLatestMetric("acs_oss", "InternetSendBytes", dim, startTime, endTime);
                double internetRecv = queryLatestMetric("acs_oss", "InternetRecvBytes", dim, startTime, endTime);
                double successRate = queryLatestMetric("acs_oss", "SuccessRate", dim, startTime, endTime);
                item.put("totalRequests", totalReq);
                item.put("internetSend", internetSend);
                item.put("internetRecv", internetRecv);
                item.put("successRate", successRate);
                list.add(item);
            }

            // 只保留有实际请求/带宽的 bucket
            list = list.stream()
                    .filter(item -> toDouble(item.get("totalRequests")) > 0
                            || toDouble(item.get("internetSend")) > 0
                            || toDouble(item.get("internetRecv")) > 0)
                    .toList();

            ossBucketsCache = list;
            ossBucketsCacheTime = now;
            log.info("OSS Bucket 监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 OSS 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 从 DB 缓存加载 List<Map> 数据（stale-while-revalidate 模式的快速展示路径）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadFromDbCache(String pageKey, String dataKey) {
        String rawJson = pageDataCacheService.getRaw(pageKey, dataKey);
        if (rawJson == null || rawJson.isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("反序列化 DB 缓存失败: pageKey={}, dataKey={}", pageKey, dataKey);
            return null;
        }
    }

    /**
     * 保存数据到 DB 缓存（10 分钟 TTL，近几日数据可查便于环比）
     */
    private void saveToDbCache(String pageKey, String dataKey, List<Map<String, Object>> data) {
        if (data != null && !data.isEmpty()) {
            pageDataCacheService.save(pageKey, dataKey, data, 10);
        }
    }

    /**
     * 查询最新指标值：按时间戳分组取最新时刻的值。
     * ShardingCommandQPS 等多序列指标（每个命令一条线）需在同一时间戳内求和才是真实总量。
     */
    private double queryLatestMetric(String namespace, String metric, String dimensions,
                                     String startTime, String endTime) {
        List<double[]> points = cloudMonitorClient.queryMetric(namespace, metric, dimensions, 60, startTime, endTime);
        if (points.isEmpty()) return 0;

        // 找最新时间戳
        double maxTs = 0;
        for (double[] p : points) {
            if (p[0] > maxTs) maxTs = p[0];
        }
        // 同一时间戳内所有序列求和（QPS 类多序列指标需要汇总，单序列指标不受影响）
        double sum = 0;
        for (double[] p : points) {
            if (p[0] == maxTs) sum += p[1];
        }
        return sum;
    }

    /**
     * 从 Grafana queryInstant 结果中提取单个数值（取第一条结果的 value）
     */
    private double extractValue(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) return 0;
        Object v = results.get(0).get("value");
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (Exception ignored) {}
        }
        return 0;
    }

    /**
     * 安全地将对象转为 double（处理 Number/String 混合类型，避免 ClassCastException）
     */
    private double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (Exception ignored) {}
        }
        return 0;
    }

    // ===== DB 分库 / Druid 连接池 / JVM 监控（从 Grafana/Aliyun Prometheus） =====

    /**
     * DB 分库监控：RDS + PolarDB 各库的 CPU/内存/IOPS/ActiveSessions（Aliyun Prometheus），5 分钟缓存
     */
    public List<Map<String, Object>> dbInstances() {
        long now = System.currentTimeMillis();
        if (dbInstancesCache != null && now - dbInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return dbInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String ds = grafanaClient.getAliyunDsUid();

            // RDS 实例（按 desc label 发现）
            var rdsRows = grafanaClient.queryInstant(
                    "count by (desc) (AliyunRds_CpuUsage{desc=~\"prod-.*\"})", ds);
            for (var row : rdsRows) {
                String desc = String.valueOf(row.getOrDefault("desc", ""));
                if (desc.isEmpty()) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceName", desc);
                item.put("engine", "RDS");

                var cpu = grafanaClient.queryInstant("AliyunRds_CpuUsage{desc=\"" + desc + "\"}", ds);
                var mem = grafanaClient.queryInstant("AliyunRds_MemoryUsage{desc=\"" + desc + "\"}", ds);
                var iops = grafanaClient.queryInstant("AliyunRds_IOPSUsage{desc=\"" + desc + "\"}", ds);
                var sess = grafanaClient.queryInstant("AliyunRds_MySQL_ActiveSessions{desc=\"" + desc + "\"}", ds);
                item.put("cpuUsage", extractValue(cpu));
                item.put("memoryUsage", extractValue(mem));
                item.put("iops", extractValue(iops));
                item.put("activeSessions", extractValue(sess));
                list.add(item);
            }

            // PolarDB 实例（按 desc label 发现）
            var polardbRows = grafanaClient.queryInstant(
                    "count by (desc) (AliyunPolardb_cluster_cpu_utilization{desc=~\"prod-.*\"})", ds);
            for (var row : polardbRows) {
                String desc = String.valueOf(row.getOrDefault("desc", ""));
                if (desc.isEmpty()) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceName", desc);
                item.put("engine", "PolarDB");

                var cpu = grafanaClient.queryInstant(
                        "avg by (desc) (AliyunPolardb_cluster_cpu_utilization{desc=\"" + desc + "\"})", ds);
                var mem = grafanaClient.queryInstant(
                        "avg by (desc) (AliyunPolardb_cluster_memory_utilization{desc=\"" + desc + "\"})", ds);
                var iops = grafanaClient.queryInstant(
                        "avg by (desc) (AliyunPolardb_cluster_iops_usage{desc=\"" + desc + "\"})", ds);
                var sess = grafanaClient.queryInstant(
                        "sum by (desc) (AliyunPolardb_cluster_active_sessions{desc=\"" + desc + "\"})", ds);
                item.put("cpuUsage", extractValue(cpu));
                item.put("memoryUsage", extractValue(mem));
                item.put("iops", extractValue(iops));
                item.put("activeSessions", extractValue(sess));
                list.add(item);
            }

            dbInstancesCache = list;
            dbInstancesCacheTime = now;
            log.info("DB 分库监控已缓存: {} 个 (RDS+PolarDB)", list.size());
        } catch (Exception e) {
            log.error("查询 DB 分库监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Druid 连接池监控：按 application 聚合活动连接/最大连接/等待线程/SQL执行速率（k8s Prometheus），5 分钟缓存
     */
    public List<Map<String, Object>> druidInstances() {
        long now = System.currentTimeMillis();
        if (druidInstancesCache != null && now - druidInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return druidInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String ds = grafanaClient.getDsUid();

            // 发现有 Druid 指标的应用
            var appRows = grafanaClient.queryInstant(
                    "count by (application) (druid_active_count)", ds);
            for (var row : appRows) {
                String app = String.valueOf(row.getOrDefault("application", ""));
                if (app.isEmpty()) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("application", app);

                var active = grafanaClient.queryInstant(
                        "sum(druid_active_count{application=\"" + app + "\"})", ds);
                var maxActive = grafanaClient.queryInstant(
                        "sum(druid_max_active{application=\"" + app + "\"})", ds);
                var wait = grafanaClient.queryInstant(
                        "sum(druid_wait_thread_count{application=\"" + app + "\"})", ds);
                var execRate = grafanaClient.queryInstant(
                        "sum(irate(druid_execute_count{application=\"" + app + "\"}[2m]))", ds);

                double activeVal = extractValue(active);
                double maxVal = extractValue(maxActive);
                item.put("activeCount", activeVal);
                item.put("maxActive", maxVal);
                item.put("waitThreadCount", extractValue(wait));
                item.put("sqlExecuteRate", extractValue(execRate));
                item.put("usageRate", maxVal > 0 ? activeVal / maxVal * 100 : 0);
                list.add(item);
            }

            // 按活动连接数降序
            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("activeCount", 0)),
                    toDouble(a.getOrDefault("activeCount", 0))));

            druidInstancesCache = list;
            druidInstancesCacheTime = now;
            log.info("Druid 连接池监控已缓存: {} 个应用", list.size());
        } catch (Exception e) {
            log.error("查询 Druid 连接池监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * JVM 监控：按 application 聚合堆内存率/GC频率/QPS/进程CPU（k8s Prometheus），5 分钟缓存
     */
    public List<Map<String, Object>> jvmInstances() {
        long now = System.currentTimeMillis();
        if (jvmInstancesCache != null && now - jvmInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return jvmInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String ds = grafanaClient.getDsUid();

            // 发现有 JVM 指标的应用
            var appRows = grafanaClient.queryInstant(
                    "count by (application) (jvm_memory_used_bytes{area=\"heap\"})", ds);
            for (var row : appRows) {
                String app = String.valueOf(row.getOrDefault("application", ""));
                if (app.isEmpty()) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("application", app);

                // 堆内存使用率
                var heap = grafanaClient.queryInstant(
                        "sum(jvm_memory_used_bytes{application=\"" + app + "\",area=\"heap\"})*100" +
                        "/sum(jvm_memory_max_bytes{application=\"" + app + "\",area=\"heap\"})", ds);
                // GC 频率
                var gc = grafanaClient.queryInstant(
                        "sum(rate(jvm_gc_pause_seconds_count{application=\"" + app + "\"}[1m]))", ds);
                // QPS
                var qps = grafanaClient.queryInstant(
                        "sum(rate(http_server_requests_seconds_count{application=\"" + app + "\"}[1m]))", ds);
                // 进程 CPU
                var cpu = grafanaClient.queryInstant(
                        "avg(system_cpu_usage{application=\"" + app + "\"})*100", ds);

                item.put("heapUsage", extractValue(heap));
                item.put("gcRate", extractValue(gc));
                item.put("qps", extractValue(qps));
                item.put("cpuUsage", extractValue(cpu));
                list.add(item);
            }

            // 按堆内存使用率降序
            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("heapUsage", 0)),
                    toDouble(a.getOrDefault("heapUsage", 0))));

            jvmInstancesCache = list;
            jvmInstancesCacheTime = now;
            log.info("JVM 监控已缓存: {} 个应用", list.size());
        } catch (Exception e) {
            log.error("查询 JVM 监控失败: {}", e.getMessage());
        }
        return list;
    }

    // ===== Top 指标（接入真实数据源） =====

    /**
     * RocketMQ Top Topics（实例级消息堆积 + Topic 列表，CloudMonitor 不支持按 Topic 维度查询）
     */
    public List<Map<String, Object>> rocketmqTopTopics() {
        long now = System.currentTimeMillis();
        if (rocketmqTopTopicsCache != null && now - rocketmqTopTopicsCacheTime < MONITOR_CACHE_TTL_MS) {
            return rocketmqTopTopicsCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var allInstances = cloudMonitorClient.listRocketMQInstances();
            if (allInstances.isEmpty()) {
                log.info("RocketMQ: 未找到任何实例");
                return list;
            }

            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var inst : allInstances) {
                String dim = "[{\"instanceId\":\"" + inst.getInstanceId() + "\"}]";
                double accumulation = queryLatestMetric("acs_mq", "MessageAccumulation", dim, startTime, endTime);
                double sendTps = queryLatestMetric("acs_mq", "SendTps", dim, startTime, endTime);
                double consumeTps = queryLatestMetric("acs_mq", "ConsumeTps", dim, startTime, endTime);

                var topics = cloudMonitorClient.listRocketMQTopics(inst.getInstanceId());
                if (topics.isEmpty()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("topic", "(实例级汇总)");
                    item.put("instanceName", inst.getInstanceName());
                    item.put("messageAccumulation", accumulation);
                    item.put("sendTps", sendTps);
                    item.put("consumeTps", consumeTps);
                    list.add(item);
                } else {
                    for (var topicInfo : topics) {
                        String topic = (String) topicInfo.get("topic");
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("topic", topic);
                        item.put("instanceName", inst.getInstanceName());
                        item.put("messageAccumulation", accumulation);
                        item.put("sendTps", sendTps);
                        item.put("consumeTps", consumeTps);
                        list.add(item);
                    }
                }
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("messageAccumulation", 0)),
                    toDouble(a.getOrDefault("messageAccumulation", 0))));

            rocketmqTopTopicsCache = list.size() > 20 ? list.subList(0, 20) : list;
            rocketmqTopTopicsCacheTime = now;
            log.info("RocketMQ Top Topics: {} 个（已缓存）", rocketmqTopTopicsCache.size());
        } catch (Exception e) {
            log.error("查询 RocketMQ Top Topics 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Kafka Top Partitions（按 Lag 排序，从 Grafana/Prometheus 获取真实 consumer lag 数据）
     */
    public List<Map<String, Object>> kafkaTopPartitions() {
        long now = System.currentTimeMillis();
        if (kafkaTopPartitionsCache != null && now - kafkaTopPartitionsCacheTime < MONITOR_CACHE_TTL_MS) {
            return kafkaTopPartitionsCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var results = grafanaClient.queryInstant(
                    "topk(50, sum by (application, topic) (kafka_consumer_fetch_manager_records_lag))");

            for (var item : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("topic", item.getOrDefault("topic", "unknown"));
                row.put("instanceName", item.getOrDefault("application", "unknown"));
                double lag = toDouble(item.getOrDefault("value", 0));
                row.put("lag", lag);
                row.put("produceTps", 0);
                row.put("consumeTps", 0);
                list.add(row);
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("lag", 0)),
                    toDouble(a.getOrDefault("lag", 0))));

            kafkaTopPartitionsCache = list.size() > 20 ? list.subList(0, 20) : list;
            kafkaTopPartitionsCacheTime = now;
            log.info("Kafka Top Partitions (from Prometheus): {} 个（已缓存）", kafkaTopPartitionsCache.size());
        } catch (Exception e) {
            log.error("查询 Kafka Top Partitions 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Redis Big Keys（按内存使用排序，使用 CloudMonitor UsedMemory 指标）
     */
    public List<Map<String, Object>> redisBigKeys() {
        long now = System.currentTimeMillis();
        if (redisBigKeysCache != null && now - redisBigKeysCacheTime < MONITOR_CACHE_TTL_MS) {
            return redisBigKeysCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = redisInstances();
            for (var inst : instances) {
                String instanceId = (String) inst.get("instanceId");
                String instanceName = (String) inst.get("instanceName");

                String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

                double usedMemory = queryLatestMetric("acs_kvstore", "UsedMemory", dim, startTime, endTime);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", instanceName);
                item.put("type", "instance");
                item.put("description", "Redis 实例内存使用");
                item.put("memoryBytes", usedMemory);
                item.put("ttl", -1);
                list.add(item);
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("memoryBytes", 0)),
                    toDouble(a.getOrDefault("memoryBytes", 0))));

            redisBigKeysCache = list.size() > 20 ? list.subList(0, 20) : list;
            redisBigKeysCacheTime = now;
            log.info("Redis Big Keys: {} 个（已缓存）", redisBigKeysCache.size());
        } catch (Exception e) {
            log.error("查询 Redis Big Keys 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Redis Slow Queries：优先从 Aliyun Prometheus 获取，CloudMonitor 作 fallback
     */
    public List<Map<String, Object>> redisSlowQueries() {
        long now = System.currentTimeMillis();
        if (redisSlowQueriesCache != null && now - redisSlowQueriesCacheTime < MONITOR_CACHE_TTL_MS) {
            return redisSlowQueriesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String aliyunDs = grafanaClient.getAliyunDsUid();

            var slowRows = grafanaClient.queryInstant(
                    "sum by (instanceId, instanceName) (AliyunRedis_SlowRequests)", aliyunDs);
            if (slowRows.isEmpty()) {
                slowRows = grafanaClient.queryInstant(
                        "sum by (instanceId, instanceName) (AliyunKvstore_SlowRequests)", aliyunDs);
            }

            if (!slowRows.isEmpty()) {
                for (var row : slowRows) {
                    String instanceId = String.valueOf(row.getOrDefault("instanceId", ""));
                    if (instanceId.isEmpty()) continue;
                    String instanceName = String.valueOf(row.getOrDefault("instanceName", instanceId));
                    Object val = row.getOrDefault("value", 0);
                    double slowCount = val instanceof Number ? ((Number) val).doubleValue() : 0;
                    if (slowCount <= 0) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", instanceId);
                    item.put("instanceName", instanceName);
                    item.put("timestamp", now);
                    item.put("slowCount", (long) slowCount);
                    item.put("metric", "Prometheus");
                    list.add(item);
                }
                log.info("Redis Slow Queries(Prometheus): {} 个", list.size());
            }

            if (list.isEmpty()) {
                log.info("Prometheus 无 Redis 慢查数据，尝试 CloudMonitor");
                var instances = redisInstances();
                String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1).format(FMT);

                String[] possibleMetrics = {"SlowRequests", "slow_requests", "Redis_SlowRequests", "slowlog"};
                for (var inst : instances) {
                    String instanceId = (String) inst.get("instanceId");
                    String instanceName = (String) inst.getOrDefault("instanceName", instanceId);
                    String dimensions = String.format("[{\"instanceId\":\"%s\"}]", instanceId);

                    for (String metricName : possibleMetrics) {
                        var dataPoints = cloudMonitorClient.queryMetric("acs_kvstore", metricName,
                                dimensions, 60, startTime, endTime);
                        if (!dataPoints.isEmpty()) {
                            for (var point : dataPoints) {
                                if (point[1] > 0) {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("instanceId", instanceId);
                                    item.put("instanceName", instanceName);
                                    item.put("timestamp", (long) point[0]);
                                    item.put("slowCount", (long) point[1]);
                                    item.put("metric", metricName);
                                    list.add(item);
                                }
                            }
                            break;
                        }
                    }
                }
                log.info("Redis Slow Queries(CloudMonitor): {} 个", list.size());
            }

            list.sort((a, b) -> Long.compare(
                    (long) toDouble(b.getOrDefault("slowCount", 0)),
                    (long) toDouble(a.getOrDefault("slowCount", 0))));

            redisSlowQueriesCache = list.size() > 50 ? list.subList(0, 50) : list;
            redisSlowQueriesCacheTime = now;
            log.info("Redis Slow Queries: {} 个（已缓存）", redisSlowQueriesCache.size());
        } catch (Exception e) {
            log.error("查询 Redis Slow Queries 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * MySQL Top Tables：从 Aliyun Prometheus 获取 RDS/PolarDB 各实例的资源使用率
     * 注：Prometheus 无 DiskUsage 指标，改用 CPU + Memory 使用率作为排序依据
     */
    public List<Map<String, Object>> mysqlTopTables() {
        long now = System.currentTimeMillis();
        if (mysqlTopTablesCache != null && now - mysqlTopTablesCacheTime < MONITOR_CACHE_TTL_MS) {
            return mysqlTopTablesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String ds = grafanaClient.getAliyunDsUid();

            var rdsInstances = grafanaClient.queryInstant(
                    "count by (instanceId, instanceName) ({__name__=~\"AliyunRds_.*\"})", ds);
            for (var row : rdsInstances) {
                String instanceId = String.valueOf(row.getOrDefault("instanceId", ""));
                if (instanceId.isEmpty()) continue;
                String instanceName = String.valueOf(row.getOrDefault("instanceName", instanceId));

                var cpu = grafanaClient.queryInstant(
                        "AliyunRds_CpuUsage{instanceId=\"" + instanceId + "\"}", ds);
                var mem = grafanaClient.queryInstant(
                        "AliyunRds_MemoryUsage{instanceId=\"" + instanceId + "\"}", ds);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", instanceName);
                item.put("instanceId", instanceId);
                item.put("engine", "RDS");
                item.put("cpuUsage", extractValue(cpu));
                item.put("memoryUsage", extractValue(mem));
                item.put("diskUsage", 0.0);
                list.add(item);
            }

            var polardbInstances = grafanaClient.queryInstant(
                    "count by (instanceId, instanceName) ({__name__=~\"AliyunPolardb_.*\"})", ds);
            for (var row : polardbInstances) {
                String instanceId = String.valueOf(row.getOrDefault("instanceId", ""));
                if (instanceId.isEmpty()) continue;
                String instanceName = String.valueOf(row.getOrDefault("instanceName", instanceId));

                var cpu = grafanaClient.queryInstant(
                        "AliyunPolardb_cluster_cpu_utilization{instanceId=\"" + instanceId + "\"}", ds);
                var mem = grafanaClient.queryInstant(
                        "AliyunPolardb_cluster_memory_utilization{instanceId=\"" + instanceId + "\"}", ds);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", instanceName);
                item.put("instanceId", instanceId);
                item.put("engine", "PolarDB");
                item.put("cpuUsage", extractValue(cpu));
                item.put("memoryUsage", extractValue(mem));
                item.put("diskUsage", 0.0);
                list.add(item);
            }

            list.sort((a, b) -> {
                double aScore = ((Number) a.getOrDefault("cpuUsage", 0)).doubleValue()
                        + ((Number) a.getOrDefault("memoryUsage", 0)).doubleValue();
                double bScore = ((Number) b.getOrDefault("cpuUsage", 0)).doubleValue()
                        + ((Number) b.getOrDefault("memoryUsage", 0)).doubleValue();
                return Double.compare(bScore, aScore);
            });

            mysqlTopTablesCache = list.size() > 50 ? list.subList(0, 50) : list;
            mysqlTopTablesCacheTime = now;
            log.info("MySQL Top Tables: {} 个（已缓存）", mysqlTopTablesCache.size());
        } catch (Exception e) {
            log.error("查询 MySQL Top Tables 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * MySQL Slow Queries（使用 CloudMonitor 指标，因 API 权限不足）
     */
    public List<Map<String, Object>> mysqlSlowQueries() {
        long now = System.currentTimeMillis();
        if (mysqlSlowQueriesCache != null && now - mysqlSlowQueriesCacheTime < MONITOR_CACHE_TTL_MS) {
            return mysqlSlowQueriesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = mysqlInstances();
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1).format(FMT);

            String[] possibleMetrics = {"SlowQueries", "slow_queries", "MySQL_SlowQueries", "mysql_slow_queries_per_second"};
            String workingMetric = null;

            for (var inst : instances) {
                String instanceId = (String) inst.get("instanceId");
                String instanceName = (String) inst.getOrDefault("instanceName", instanceId);
                String dimensions = String.format("[{\"instanceId\":\"%s\"}]", instanceId);

                for (String metricName : possibleMetrics) {
                    var dataPoints = cloudMonitorClient.queryMetric("acs_rds_dashboard", metricName,
                            dimensions, 60, startTime, endTime);

                    if (!dataPoints.isEmpty()) {
                        workingMetric = metricName;
                        for (var point : dataPoints) {
                            if (point[1] > 0) {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("instanceId", instanceId);
                                item.put("instanceName", instanceName);
                                item.put("timestamp", (long) point[0]);
                                item.put("slowCount", (long) point[1]);
                                item.put("metric", metricName);
                                list.add(item);
                            }
                        }
                        break;
                    }
                }
            }

            if (workingMetric != null) {
                log.info("MySQL 慢查询使用指标: {}", workingMetric);
            }

            list.sort((a, b) -> Long.compare(
                    (long) toDouble(b.getOrDefault("slowCount", 0)),
                    (long) toDouble(a.getOrDefault("slowCount", 0))));

            mysqlSlowQueriesCache = list.size() > 50 ? list.subList(0, 50) : list;
            mysqlSlowQueriesCacheTime = now;
            log.info("MySQL Slow Queries: {} 个（已缓存）", mysqlSlowQueriesCache.size());
        } catch (Exception e) {
            log.error("查询 MySQL Slow Queries 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Lindorm Top Tables（按 QPS 排序，使用 CloudMonitor 实例级指标）
     */
    public List<Map<String, Object>> lindormTopTables() {
        long now = System.currentTimeMillis();
        if (lindormTopTablesCache != null && now - lindormTopTablesCacheTime < MONITOR_CACHE_TTL_MS) {
            return lindormTopTablesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = lindormInstances();
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var inst : instances) {
                String instanceId = (String) inst.get("instanceId");
                String instanceName = (String) inst.get("instanceName");

                String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                double readQps = queryLatestMetric("acs_lindorm", "ReadQps", dim, startTime, endTime);
                double writeQps = queryLatestMetric("acs_lindorm", "WriteQps", dim, startTime, endTime);
                double storageUsage = queryLatestMetric("acs_lindorm", "StorageUsage", dim, startTime, endTime);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", instanceName);
                item.put("description", "Lindorm 实例");
                item.put("readQps", readQps);
                item.put("writeQps", writeQps);
                item.put("storageMB", storageUsage / 1024 / 1024);
                list.add(item);
            }

            list.sort((a, b) -> {
                double totalA = ((Number) a.getOrDefault("readQps", 0)).doubleValue()
                        + ((Number) a.getOrDefault("writeQps", 0)).doubleValue();
                double totalB = ((Number) b.getOrDefault("readQps", 0)).doubleValue()
                        + ((Number) b.getOrDefault("writeQps", 0)).doubleValue();
                return Double.compare(totalB, totalA);
            });

            lindormTopTablesCache = list.size() > 20 ? list.subList(0, 20) : list;
            lindormTopTablesCacheTime = now;
            log.info("Lindorm Top Tables: {} 个（已缓存）", lindormTopTablesCache.size());
        } catch (Exception e) {
            log.error("查询 Lindorm Top Tables 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Elasticsearch Top Indices（按文档数和存储排序，使用 CloudMonitor 实例级指标）
     */
    public List<Map<String, Object>> elasticsearchTopIndices() {
        long now = System.currentTimeMillis();
        if (elasticsearchTopIndicesCache != null && now - elasticsearchTopIndicesCacheTime < MONITOR_CACHE_TTL_MS) {
            return elasticsearchTopIndicesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = elasticsearchInstances();
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var inst : instances) {
                String instanceId = (String) inst.get("instanceId");
                String instanceName = (String) inst.get("instanceName");

                String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                double diskUsage = queryLatestMetric("acs_elasticsearch", "NodeDiskUtilization", dim, startTime, endTime);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("indexName", instanceName);
                item.put("description", "Elasticsearch 实例");
                item.put("docCount", 0);
                item.put("storageGB", diskUsage / 1024);
                item.put("shardCount", 0);
                item.put("replicaCount", 0);
                list.add(item);
            }

            list.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("storageGB", 0)).doubleValue(),
                    ((Number) a.getOrDefault("storageGB", 0)).doubleValue()));

            elasticsearchTopIndicesCache = list.size() > 20 ? list.subList(0, 20) : list;
            elasticsearchTopIndicesCacheTime = now;
            log.info("Elasticsearch Top Indices: {} 个（已缓存）", elasticsearchTopIndicesCache.size());
        } catch (Exception e) {
            log.error("查询 Elasticsearch Top Indices 失败: {}", e.getMessage());
        }
        return list;
    }
}
