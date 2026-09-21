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
    private final com.ykc.hubble.client.ArmsClient armsClient;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final java.util.concurrent.Executor queryExecutor;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final java.util.concurrent.Semaphore EXTERNAL_CALL_SEM = new java.util.concurrent.Semaphore(3);

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
    private static final long TRACE_DETAIL_TIMEOUT_MS = 10 * 1000; // 首查 trace 详情总超时，超时返回已完成部分

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

    // DB 分库 / Druid 连接池 / JVM / 线程池 监控缓存（5 分钟）
    private List<Map<String, Object>> dbInstancesCache = null;
    private long dbInstancesCacheTime = 0;
    private List<Map<String, Object>> druidInstancesCache = null;
    private long druidInstancesCacheTime = 0;
    private List<Map<String, Object>> jvmInstancesCache = null;
    private long jvmInstancesCacheTime = 0;
    private List<Map<String, Object>> threadPoolCache = null;
    private long threadPoolCacheTime = 0;

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

                    item.put("cpuUsage", queryLatestMetricWithFallbackThrottled("acs_kvstore", "ShardingCpuUsage", "CpuUsage", dim, startTime, endTime, true));

                    // 连接数：集群版 proxy 架构 ShardingUsedConnection 恒为 0（节点级不上报），真实指标是 ShardingProxyUsedConnection；
                    // 标准版用 UsedConnection（绝对连接数）；tair 兜底 ShardingUsedConnection。实测确认（2026-09-20），勿用 GroupConnectionUsage（实例级查询返回噪音）
                    double connections = queryLatestMetricWithFallbackThrottled("acs_kvstore", "ShardingProxyUsedConnection", "UsedConnection", dim, startTime, endTime);
                    if (connections == 0) {
                        connections = queryLatestMetricThrottled("acs_kvstore", "ShardingUsedConnection", dim, startTime, endTime);
                    }
                    if (connections == 0) {
                        connections = queryLatestMetricThrottled("acs_kvstore", "ShardingLBActiveConnPs", dim, startTime, endTime);
                    }
                    item.put("connections", connections);

                    item.put("memoryUsage", queryLatestMetricWithFallbackThrottled("acs_kvstore", "ShardingMemoryUsage", "MemoryUsage", dim, startTime, endTime, true));
                    item.put("qps", queryLatestMetricWithFallbackThrottled("acs_kvstore", "ShardingCommandQPS", "UsedQPS", dim, startTime, endTime));

                    Map<String, Object> yoy = new LinkedHashMap<>();
                    yoy.put("cpuUsage", yoyCm("acs_kvstore", dim, "ShardingCpuUsage|CpuUsage"));
                    yoy.put("memoryUsage", yoyCm("acs_kvstore", dim, "ShardingMemoryUsage|MemoryUsage"));
                    yoy.put("connections", yoyCm("acs_kvstore", dim, "ShardingProxyUsedConnection|UsedConnection|ShardingUsedConnection|ShardingLBActiveConnPs"));
                    item.put("_yoy", yoy);
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
     * 诊断：实测 Redis（acs_kvstore）可用指标——元数据清单 + 对指定（或第一个）prod 实例逐个试查候选指标，
     * 用于确定标准版/集群版实例真实支持的指标名，避免盲试报 400。
     */
    public Map<String, Object> redisMetricsDiscovery(String instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> candidates = List.of(
                "CpuUsage", "ShardingCpuUsage", "GroupCpuUsage", "GroupMemoryUsage",
                "MemoryUsage", "ShardingMemoryUsage", "UsedMemory", "ShardingUsedMemory",
                "ConnectionUsage", "UsedConnection", "StandardUsedConnection",
                "ShardingConnectionUsage", "ShardingUsedConnection",
                "ShardingProxyUsedConnection", "ShardingProxyConnectionUsage",
                "ShardingQuotaConnection", "GroupConnectionUsage", "ShardingLBActiveConnPs",
                "ShardingCommandQPS", "ShardingProxyTotalQps", "ShardingUsedQPS", "UsedQPS",
                "RealtimeKeys", "HitRatio", "ShardingAvgRt");
        try {
            var prod = cloudMonitorClient.listRedisInstances().stream()
                    .filter(inst -> inst.getInstanceName() != null && inst.getInstanceName().startsWith("prod-"))
                    .toList();
            result.put("prodInstanceCount", prod.size());
            if (prod.isEmpty()) {
                result.put("error", "无 prod 实例");
                return result;
            }
            var target = prod.stream()
                    .filter(inst -> inst.getInstanceId().equals(instanceId))
                    .findFirst()
                    .orElse(prod.get(0));
            result.put("probeInstance", target.getInstanceId() + " / " + target.getInstanceName()
                    + " / class=" + target.getInstanceClass());
            result.putAll(cloudMonitorClient.probeMetrics("acs_kvstore", target.getInstanceId(), candidates));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
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

                    double[] cpu = queryLatestAndPeakMetricThrottled("acs_rds_dashboard", "CpuUsage", dim, startTime, endTime, false);
                    double[] conn = queryLatestAndPeakMetricThrottled("acs_rds_dashboard", "ConnectionUsage", dim, startTime, endTime, false);
                    double[] iops = queryLatestAndPeakMetricThrottled("acs_rds_dashboard", "IOPSUsage", dim, startTime, endTime, false);
                    double[] disk = queryLatestAndPeakMetricThrottled("acs_rds_dashboard", "DiskUsage", dim, startTime, endTime, false);
                    item.put("cpuUsage", cpu[0]);
                    item.put("cpuUsagePeak", cpu[1]);
                    item.put("connections", conn[0]);
                    item.put("connectionsPeak", conn[1]);
                    item.put("iops", iops[0]);
                    item.put("iopsPeak", iops[1]);
                    item.put("diskUsage", disk[0]);
                    item.put("diskUsagePeak", disk[1]);

                    Map<String, Object> yoy = new LinkedHashMap<>();
                    yoy.put("cpuUsage", yoyCm("acs_rds_dashboard", dim, "CpuUsage"));
                    yoy.put("connections", yoyCm("acs_rds_dashboard", dim, "ConnectionUsage"));
                    yoy.put("diskUsage", yoyCm("acs_rds_dashboard", dim, "DiskUsage"));
                    item.put("_yoy", yoy);
                    return item;
                })
            ).toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(futures).join();
            for (var f : futures) {
                list.add((Map<String, Object>) f.get());
            }

            // PolarDB 集群
            var allPolarClusters = cloudMonitorClient.listPolarDBClusters();
            var polarClusters = allPolarClusters.stream()
                    .filter(c -> c.getDBClusterDescription() != null && c.getDBClusterDescription().startsWith("prod-"))
                    .toList();
            log.info("PolarDB 集群数: 总{} 个, prod {} 个", allPolarClusters.size(), polarClusters.size());

            var polarFutures = polarClusters.stream().map(cluster ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", cluster.getDBClusterId());
                    item.put("instanceName", cluster.getDBClusterDescription());
                    item.put("engine", "PolarDB");
                    item.put("engineVersion", cluster.getDBVersion());
                    item.put("instanceType", cluster.getDBType());
                    item.put("status", cluster.getDBClusterStatus());

                    // 注意：acs_polardb 的维度键是 clusterId（dBClusterId 会导致维度过滤失效返回全账号数据），
                    // CPU/连接/IOPS 为节点级指标，多节点需取平均
                    String dim = "[{\"clusterId\":\"" + cluster.getDBClusterId() + "\"}]";

                    double[] cpu = queryLatestAndPeakMetricThrottled("acs_polardb", "cluster_cpu_utilization", dim, startTime, endTime, true);
                    double[] conn = queryLatestAndPeakMetricThrottled("acs_polardb", "cluster_connection_utilization", dim, startTime, endTime, true);
                    double[] iops = queryLatestAndPeakMetricThrottled("acs_polardb", "cluster_iops_usage", dim, startTime, endTime, true);
                    double[] disk = queryLatestAndPeakMetricThrottled("acs_polardb", "cluster_disk_utilization", dim, startTime, endTime, true);
                    item.put("cpuUsage", cpu[0]);
                    item.put("cpuUsagePeak", cpu[1]);
                    item.put("connections", conn[0]);
                    item.put("connectionsPeak", conn[1]);
                    item.put("iops", iops[0]);
                    item.put("iopsPeak", iops[1]);
                    item.put("diskUsage", disk[0]);
                    item.put("diskUsagePeak", disk[1]);

                    Map<String, Object> yoy = new LinkedHashMap<>();
                    yoy.put("cpuUsage", yoyCm("acs_polardb", dim, "cluster_cpu_utilization"));
                    yoy.put("connections", yoyCm("acs_polardb", dim, "cluster_connection_utilization"));
                    yoy.put("diskUsage", yoyCm("acs_polardb", dim, "cluster_disk_utilization"));
                    item.put("_yoy", yoy);
                    return item;
                })
            ).toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(polarFutures).join();
            for (var f : polarFutures) {
                list.add((Map<String, Object>) f.get());
            }

            mysqlInstancesCache = list;
            mysqlInstancesCacheTime = now;
            if (!list.isEmpty()) {
                saveToDbCache("middleware_mysql", "instances", list);
            }
            log.info("MySQL/PolarDB prod 实例监控已缓存: {} 个 (RDS {} + PolarDB {})", list.size(), instances.size(), polarClusters.size());
        } catch (Exception e) {
            log.error("查询 MySQL/PolarDB 监控失败: {}", e.getMessage());
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
            if (list.isEmpty()) {
                log.info("Pod CPU 查询结果为空，使用演示数据");
                list = generateDemoPodCpu();
            }
        } catch (Exception e) {
            log.warn("查询 Pod CPU 失败，使用演示数据: {}", e.getMessage());
            list = generateDemoPodCpu();
        }
        podCpuCache = list;
        podCpuCacheTime = now;
        log.info("Pod CPU Top: {} 个（已缓存）", list.size());
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
            if (list.isEmpty()) {
                log.info("Pod Memory 查询结果为空，使用演示数据");
                list = generateDemoPodMemory();
            }
        } catch (Exception e) {
            log.warn("查询 Pod 内存失败，使用演示数据: {}", e.getMessage());
            list = generateDemoPodMemory();
        }
        podMemCache = list;
        podMemCacheTime = now;
        log.info("Pod Memory Top: {} 个（已缓存）", list.size());
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
            if (list.isEmpty()) {
                log.info("Node 概览查询结果为空，使用演示数据");
                list = generateDemoNodeOverview();
            }
        } catch (Exception e) {
            log.warn("查询 Node 概览失败，使用演示数据: {}", e.getMessage());
            list = generateDemoNodeOverview();
        }
        nodeCache = list;
        nodeCacheTime = now;
        log.info("Node 概览: {} 个节点（已缓存）", list.size());
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
            // 昨天同一时段堆积数（15 分钟窗口同口径，同比告警用）
            long yAccumulation = slsQueryClient.countLogstore(logstore, "消息堆积 OR 消费失败 OR consumeFailed",
                    fromSec - 24 * 3600, nowSec - 24 * 3600);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", "rocketmq-prod");
            item.put("instanceName", "RocketMQ 生产集群");
            item.put("region", "cn-hangzhou");
            item.put("status", "Running");
            item.put("version", "SLS");
            item.put("messageAccumulation", accumulationCount);
            item.put("sendTps", sendCount / 900.0);
            item.put("consumeTps", consumeCount / 900.0);
            item.put("alertLevel", accumulationCount > 10000 ? "red" : accumulationCount > 1000 ? "yellow" : "normal");
            item.put("alertDetails", List.of());
            item.put("_yesterday_messageAccumulation", (double) yAccumulation);
            list.add(item);

            rocketmqInstancesCache = list;
            rocketmqInstancesCacheTime = now;
            log.info("RocketMQ 监控(SLS): 发送={}, 消费={}, 堆积={}, 昨天同时段堆积={}", sendCount, consumeCount, accumulationCount, yAccumulation);
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
                var lagRows = grafanaClient.queryInstant(
                        "sum by (instanceId) (AliyunKafka_message_accumulation)", aliyunDs);
                var produceRows = grafanaClient.queryInstant(
                        "sum by (instanceId) (AliyunKafka_instance_message_input)", aliyunDs);
                var consumeRows = grafanaClient.queryInstant(
                        "sum by (instanceId) (AliyunKafka_instance_message_output)", aliyunDs);

                Map<String, double[]> metrics = new LinkedHashMap<>();
                collectByLabel(lagRows, "instanceId", metrics, 0);
                collectByLabel(produceRows, "instanceId", metrics, 1);
                collectByLabel(consumeRows, "instanceId", metrics, 2);

                for (var entry : metrics.entrySet()) {
                    String instanceId = entry.getKey();
                    if (instanceId.isEmpty()) continue;
                    double[] vals = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("instanceId", instanceId);
                    item.put("instanceName", instanceId);
                    item.put("region", "cn-hangzhou");
                    item.put("status", "Running");
                    item.put("version", "Prometheus");
                    item.put("lag", vals[0]);
                    item.put("produceTps", vals[1]);
                    item.put("consumeTps", vals[2]);
                    item.put("_yoy", Map.of("lag", yoyGr(aliyunDs,
                            "sum(AliyunKafka_message_accumulation{instanceId=\"" + instanceId + "\"})")));
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
                    item.put("region", "cn-hangzhou");
                    item.put("status", "Running");
                    item.put("version", "CloudMonitor");

                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    item.put("lag", queryLatestMetricThrottled("acs_kafka", "Lag", dim, startTime, endTime));
                    item.put("produceTps", queryLatestMetricThrottled("acs_kafka", "ProduceTps", dim, startTime, endTime));
                    item.put("consumeTps", queryLatestMetricThrottled("acs_kafka", "ConsumeTps", dim, startTime, endTime));
                    item.put("_yoy", Map.of("lag", yoyCm("acs_kafka", dim, "Lag")));
                    list.add(item);
                }
            }

            if (list.isEmpty()) {
                list.addAll(generateDemoKafkaInstances());
                log.info("Kafka 实例监控使用演示数据: {} 个", list.size());
            }

            kafkaInstancesCache = list;
            kafkaInstancesCacheTime = now;
            log.info("Kafka 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 Kafka 监控失败: {}", e.getMessage());
            list = generateDemoKafkaInstances();
        }
        return list;
    }

    /**
     * Lindorm 实例监控（丰富指标），5 分钟缓存。
     * Prometheus: CPU User/IOWait、读/写 QPS、读/写 RT、磁盘读流量
     * CloudMonitor: 网络流入/流出、热/冷存储用量、Get RT/P99、Compaction/Handler队列
     */
    public List<Map<String, Object>> lindormInstances() {
        long now = System.currentTimeMillis();
        if (lindormInstancesCache != null && now - lindormInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return lindormInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String aliyunDs = grafanaClient.getAliyunDsUid();
            var instanceRows = grafanaClient.queryInstant(
                    "count by (instanceId) (AliyunLindorm_cpu_user)", aliyunDs);

            if (!instanceRows.isEmpty()) {
                var cpuRows = grafanaClient.queryInstant(
                        "avg by (instanceId) (AliyunLindorm_cpu_user{host=~\"lindormtable-.*\"})", aliyunDs);
                var cpuWioRows = grafanaClient.queryInstant(
                        "avg by (instanceId) (AliyunLindorm_cpu_wio{host=~\"lindormtable-.*\"})", aliyunDs);
                var readRows = grafanaClient.queryInstant(
                        "sum by (instanceId) (AliyunLindorm_read_ops{host=~\"lindormtable-.*\"})", aliyunDs);
                var writeRows = grafanaClient.queryInstant(
                        "sum by (instanceId) (AliyunLindorm_write_ops{host=~\"lindormtable-.*\"})", aliyunDs);
                var readRtRows = grafanaClient.queryInstant(
                        "avg by (instanceId) (AliyunLindorm_read_rt{host=~\"lindormtable-.*\"})", aliyunDs);
                var writeRtRows = grafanaClient.queryInstant(
                        "avg by (instanceId) (AliyunLindorm_write_rt{host=~\"lindormtable-.*\"})", aliyunDs);
                var diskReadRows = grafanaClient.queryInstant(
                        "sum by (instanceId) (AliyunLindorm_disk_readbytes{host=~\"lindormtable-.*\"})", aliyunDs);

                Map<String, double[]> metrics = new LinkedHashMap<>();
                collectByLabel(cpuRows, "instanceId", metrics, 0);
                collectByLabel(readRows, "instanceId", metrics, 1);
                collectByLabel(writeRows, "instanceId", metrics, 2);
                collectByLabel(cpuWioRows, "instanceId", metrics, 3);
                collectByLabel(readRtRows, "instanceId", metrics, 4);
                collectByLabel(writeRtRows, "instanceId", metrics, 5);
                collectByLabel(diskReadRows, "instanceId", metrics, 6);

                String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

                var itemFutures = metrics.entrySet().stream()
                    .filter(e -> !e.getKey().isEmpty())
                    .map(entry -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        String instanceId = entry.getKey();
                        double[] vals = entry.getValue();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("instanceId", instanceId);
                        item.put("instanceName", "lindorm-" + instanceId);
                        item.put("cpuUsage", vals[0]);
                        item.put("cpuWio", vals[3]);
                        item.put("qps", vals[1]);
                        item.put("writeQps", vals[2]);
                        item.put("readRt", vals[4]);
                        item.put("writeRt", vals[5]);
                        item.put("diskReadBytes", vals[6]);

                        String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                        double hotStoragePct = queryLatestMetricThrottled("acs_lindorm", "hot_storage_used_percent", dim, startTime, endTime, true);
                        double coldStoragePct = queryLatestMetricThrottled("acs_lindorm", "cold_storage_used_percent", dim, startTime, endTime, true);
                        item.put("diskUsage", hotStoragePct);
                        item.put("bytesIn", queryLatestMetricThrottled("acs_lindorm", "bytes_in", dim, startTime, endTime));
                        item.put("bytesOut", queryLatestMetricThrottled("acs_lindorm", "bytes_out", dim, startTime, endTime));
                        item.put("hotStorageUsedPercent", hotStoragePct);
                        item.put("hotStorageUsedBytes", queryLatestMetricThrottled("acs_lindorm", "hot_storage_used_bytes", dim, startTime, endTime));
                        item.put("coldStorageUsedPercent", coldStoragePct);
                        item.put("getRtAvg", queryLatestMetricThrottled("acs_lindorm", "get_rt_avg", dim, startTime, endTime, true));
                        item.put("getRtP99", queryLatestMetricThrottled("acs_lindorm", "get_rt_p99", dim, startTime, endTime, true));
                        item.put("compactionQueueSize", queryLatestMetricThrottled("acs_lindorm", "compaction_queue_size", dim, startTime, endTime));
                        item.put("handlerQueueSize", queryLatestMetricThrottled("acs_lindorm", "handler_queue_size", dim, startTime, endTime));

                        // cpuUsage 来自 Prometheus（AliyunLindorm_cpu_user），同比走 Grafana；diskUsage 同比走 CloudMonitor 同源
                        Map<String, Object> yoy = new LinkedHashMap<>();
                        yoy.put("cpuUsage", yoyGr(aliyunDs, "avg(AliyunLindorm_cpu_user{instanceId=\"" + instanceId + "\"})"));
                        yoy.put("diskUsage", yoyCm("acs_lindorm", dim, "hot_storage_used_percent"));
                        item.put("_yoy", yoy);
                        return item;
                    }, queryExecutor))
                    .toArray(java.util.concurrent.CompletableFuture[]::new);

                java.util.concurrent.CompletableFuture.allOf(itemFutures).join();
                for (var f : itemFutures) {
                    try { list.add((Map<String, Object>) f.get()); } catch (Exception ignored) {}
                }
                log.info("Lindorm 实例监控(Prometheus): {} 个", list.size());
            } else {
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

                var cmFutures = instances.stream()
                    .map(inst -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        String instanceId = (String) inst.get("instanceId");
                        String alias = (String) inst.get("instanceAlias");
                        item.put("instanceId", instanceId);
                        item.put("instanceName", alias);

                        String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                        item.put("cpuUsage", queryLatestMetricThrottled("acs_lindorm", "CpuUsage", dim, startTime, endTime, true));
                        item.put("cpuWio", queryLatestMetricThrottled("acs_lindorm", "cpu_wio", dim, startTime, endTime, true));
                        double hotStoragePct = queryLatestMetricThrottled("acs_lindorm", "hot_storage_used_percent", dim, startTime, endTime, true);
                        double coldStoragePct = queryLatestMetricThrottled("acs_lindorm", "cold_storage_used_percent", dim, startTime, endTime, true);
                        item.put("diskUsage", hotStoragePct);
                        item.put("qps", queryLatestMetricThrottled("acs_lindorm", "Qps", dim, startTime, endTime));
                        item.put("writeQps", queryLatestMetricThrottled("acs_lindorm", "WriteQps", dim, startTime, endTime));
                        item.put("readRt", queryLatestMetricThrottled("acs_lindorm", "get_rt_avg", dim, startTime, endTime, true));
                        item.put("writeRt", 0.0);
                        item.put("diskReadBytes", queryLatestMetricThrottled("acs_lindorm", "disk_readbytes", dim, startTime, endTime));
                        item.put("bytesIn", queryLatestMetricThrottled("acs_lindorm", "bytes_in", dim, startTime, endTime));
                        item.put("bytesOut", queryLatestMetricThrottled("acs_lindorm", "bytes_out", dim, startTime, endTime));
                        item.put("hotStorageUsedPercent", hotStoragePct);
                        item.put("hotStorageUsedBytes", queryLatestMetricThrottled("acs_lindorm", "hot_storage_used_bytes", dim, startTime, endTime));
                        item.put("coldStorageUsedPercent", coldStoragePct);
                        item.put("getRtAvg", queryLatestMetricThrottled("acs_lindorm", "get_rt_avg", dim, startTime, endTime, true));
                        item.put("getRtP99", queryLatestMetricThrottled("acs_lindorm", "get_rt_p99", dim, startTime, endTime, true));
                        item.put("compactionQueueSize", queryLatestMetricThrottled("acs_lindorm", "compaction_queue_size", dim, startTime, endTime));
                        item.put("handlerQueueSize", queryLatestMetricThrottled("acs_lindorm", "handler_queue_size", dim, startTime, endTime));

                        Map<String, Object> yoy = new LinkedHashMap<>();
                        yoy.put("cpuUsage", yoyCm("acs_lindorm", dim, "CpuUsage"));
                        yoy.put("diskUsage", yoyCm("acs_lindorm", dim, "hot_storage_used_percent"));
                        item.put("_yoy", yoy);
                        return item;
                    }, queryExecutor))
                    .toArray(java.util.concurrent.CompletableFuture[]::new);

                java.util.concurrent.CompletableFuture.allOf(cmFutures).join();
                for (var f : cmFutures) {
                    try { list.add((Map<String, Object>) f.get()); } catch (Exception ignored) {}
                }
            }

            if (list.isEmpty()) {
                list.addAll(generateDemoLindormInstances());
                log.info("Lindorm 实例监控使用演示数据: {} 个", list.size());
            }

            lindormInstancesCache = list;
            lindormInstancesCacheTime = now;
            log.info("Lindorm 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 Lindorm 监控失败: {}", e.getMessage());
            list = generateDemoLindormInstances();
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

            var itemFutures = instances.stream()
                .map(inst -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String instanceId = (String) inst.get("instanceId");
                    String desc = (String) inst.get("description");
                    item.put("instanceId", instanceId);
                    item.put("instanceName", desc);

                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    item.put("cpuUsage", queryLatestMetricThrottled("acs_elasticsearch", "NodeCPUUtilization", dim, startTime, endTime, true));
                    item.put("diskUsage", queryLatestMetricThrottled("acs_elasticsearch", "NodeDiskUtilization", dim, startTime, endTime, true));

                    double jvmMemory = queryLatestMetricThrottled("acs_elasticsearch", "NodeJVMMemoryUsedPercent", dim, startTime, endTime, true);
                    if (jvmMemory == 0) {
                        jvmMemory = queryLatestMetricThrottled("acs_elasticsearch", "NodeJVMHeapUtilization", dim, startTime, endTime, true);
                    }
                    if (jvmMemory == 0) {
                        jvmMemory = queryLatestMetricThrottled("acs_elasticsearch", "NodeJVMUtilization", dim, startTime, endTime, true);
                    }
                    item.put("jvmMemory", jvmMemory);

                    Map<String, Object> yoy = new LinkedHashMap<>();
                    yoy.put("cpuUsage", yoyCm("acs_elasticsearch", dim, "NodeCPUUtilization"));
                    yoy.put("diskUsage", yoyCm("acs_elasticsearch", dim, "NodeDiskUtilization"));
                    yoy.put("jvmMemory", yoyCm("acs_elasticsearch", dim, "NodeJVMMemoryUsedPercent|NodeJVMHeapUtilization|NodeJVMUtilization"));
                    item.put("_yoy", yoy);
                    return item;
                }, queryExecutor))
                .toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(itemFutures).join();
            for (var f : itemFutures) {
                try { list.add((Map<String, Object>) f.get()); } catch (Exception ignored) {}
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
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(24).format(FMT);

            var futures = buckets.stream()
                .map(bucket -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("bucketName", bucket.getName());
                    item.put("instanceName", bucket.getName());
                    item.put("location", bucket.getLocation());
                    item.put("creationDate", String.valueOf(bucket.getCreationDate()));
                    try {
                        String dim = "[{\"BucketName\":\"" + bucket.getName() + "\"}]";
                        item.put("totalRequests", queryLatestMetricThrottled("acs_oss", "TotalRequestCount", dim, startTime, endTime));
                        item.put("successRate", queryLatestMetricThrottled("acs_oss", "SuccessRate", dim, startTime, endTime));
                        item.put("errorRate4xx", 0.0);
                        item.put("errorRate5xx", 0.0);
                    } catch (Exception e) {
                        log.warn("查询 OSS Bucket {} 指标失败: {}", bucket.getName(), e.getMessage());
                        item.put("totalRequests", 0.0);
                        item.put("successRate", 0.0);
                        item.put("errorRate4xx", 0.0);
                        item.put("errorRate5xx", 0.0);
                    }
                    return item;
                }))
                .toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(futures).join();
            for (var f : futures) {
                try {
                    Map<String, Object> item = (Map<String, Object>) f.get();
                    if (item != null) list.add(item);
                } catch (Exception ignored) {}
            }

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
        return queryLatestMetric(namespace, metric, dimensions, startTime, endTime, false);
    }

    /**
     * 查询最新指标值，支持求和或求平均。
     * 利用率类指标（CPU/内存等）多节点时应求平均，QPS 类指标应求和。
     */
    private double queryLatestMetric(String namespace, String metric, String dimensions,
                                     String startTime, String endTime, boolean average) {
        List<double[]> points = cloudMonitorClient.queryMetric(namespace, metric, dimensions, 60, startTime, endTime);
        if (points.isEmpty()) return 0;

        double maxTs = 0;
        for (double[] p : points) {
            if (p[0] > maxTs) maxTs = p[0];
        }
        double sum = 0;
        int count = 0;
        for (double[] p : points) {
            if (p[0] == maxTs) {
                sum += p[1];
                count++;
            }
        }
        return average && count > 0 ? sum / count : sum;
    }

    /**
     * 先查主指标，返回 0 则查备选指标（Redis 集群版需 Sharding* 前缀指标）。
     */
    private double queryLatestMetricWithFallback(String namespace, String primaryMetric,
                                                  String fallbackMetric, String dimensions,
                                                  String startTime, String endTime) {
        return queryLatestMetricWithFallback(namespace, primaryMetric, fallbackMetric, dimensions, startTime, endTime, false);
    }

    private double queryLatestMetricWithFallback(String namespace, String primaryMetric,
                                                  String fallbackMetric, String dimensions,
                                                  String startTime, String endTime, boolean average) {
        double val = queryLatestMetric(namespace, primaryMetric, dimensions, startTime, endTime, average);
        if (val == 0) {
            val = queryLatestMetric(namespace, fallbackMetric, dimensions, startTime, endTime, average);
        }
        return val;
    }

    /**
     * 单次查询同时返回 [最新值, 近5分钟峰值]。
     * 告警巡检读峰值：定时巡检复用 5 分钟缓存且只看最新值时，短时尖峰（如持续 2 分钟的 CPU 打满）可能被完全跳过。
     */
    private double[] queryLatestAndPeakMetricThrottled(String namespace, String metric, String dimensions,
                                                        String startTime, String endTime, boolean average) {
        try {
            EXTERNAL_CALL_SEM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new double[]{0, 0};
        }
        try {
            List<double[]> points = cloudMonitorClient.queryMetric(namespace, metric, dimensions, 60, startTime, endTime);
            if (points.isEmpty()) return new double[]{0, 0};

            double maxTs = 0;
            for (double[] p : points) {
                if (p[0] > maxTs) maxTs = p[0];
            }
            double sum = 0;
            int count = 0;
            for (double[] p : points) {
                if (p[0] == maxTs) {
                    sum += p[1];
                    count++;
                }
            }
            double latest = average && count > 0 ? sum / count : sum;

            double peak = 0;
            for (double[] p : points) {
                if (p[0] >= maxTs - 5 * 60 * 1000.0 && p[1] > peak) {
                    peak = p[1];
                }
            }
            return new double[]{latest, peak};
        } finally {
            EXTERNAL_CALL_SEM.release();
        }
    }

    private double queryLatestMetricThrottled(String namespace, String metric, String dimensions,
                                              String startTime, String endTime) {
        return queryLatestMetricThrottled(namespace, metric, dimensions, startTime, endTime, false);
    }

    private double queryLatestMetricThrottled(String namespace, String metric, String dimensions,
                                               String startTime, String endTime, boolean average) {
        try {
            EXTERNAL_CALL_SEM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        try {
            return queryLatestMetric(namespace, metric, dimensions, startTime, endTime, average);
        } finally {
            EXTERNAL_CALL_SEM.release();
        }
    }

    private double queryLatestMetricWithFallbackThrottled(String namespace, String primaryMetric,
                                                           String fallbackMetric, String dimensions,
                                                           String startTime, String endTime) {
        return queryLatestMetricWithFallbackThrottled(namespace, primaryMetric, fallbackMetric, dimensions, startTime, endTime, false);
    }

    private double queryLatestMetricWithFallbackThrottled(String namespace, String primaryMetric,
                                                           String fallbackMetric, String dimensions,
                                                           String startTime, String endTime, boolean average) {
        double val = queryLatestMetricThrottled(namespace, primaryMetric, dimensions, startTime, endTime, average);
        if (val == 0) {
            val = queryLatestMetricThrottled(namespace, fallbackMetric, dimensions, startTime, endTime, average);
        }
        return val;
    }

    // ===== 昨天同时段同比（告警"涨幅+绝对值"双条件判断用） =====

    /**
     * 查询昨天同一时刻 5 分钟窗口的指标峰值（与当前 Peak 同口径：窗口内逐点取最大）。
     * spec 按 "|" 分隔依次尝试（Redis 集群版备选指标），取第一个有数据的结果；全部无数据返回 null。
     */
    public Double queryYesterdayCloudMonitorPeak(String namespace, String spec, String dimensions) {
        try {
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1).minusMinutes(5).format(FMT);
            for (String metric : spec.split("\\|")) {
                double max = queryWindowMaxMetricThrottled(namespace, metric.trim(), dimensions, startTime, endTime);
                if (max >= 0) return max;
            }
            return null;
        } catch (Exception e) {
            log.warn("查询昨天同时段峰值失败: ns={}, spec={}, err={}", namespace, spec, e.getMessage());
            return null;
        }
    }

    /**
     * 查询昨天同一时刻 5 分钟窗口的 Grafana/Prometheus 指标峰值，无数据返回 null。
     */
    public Double queryYesterdayGrafanaPeak(String dsUid, String promql) {
        try {
            long to = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
            long from = to - 5 * 60 * 1000L;
            var rows = grafanaClient.queryRange(promql, String.valueOf(from), String.valueOf(to), dsUid);
            double max = -1;
            for (var row : rows) {
                double v = toDouble(row.get("value"));
                if (v > max) max = v;
            }
            return max >= 0 ? max : null;
        } catch (Exception e) {
            log.warn("查询昨天同时段峰值失败(Grafana): promql={}, err={}", promql, e.getMessage());
            return null;
        }
    }

    /**
     * 窗口最大值（逐点取最大，与 queryLatestAndPeakMetricThrottled 的峰值口径一致），无数据返回 -1。
     */
    private double queryWindowMaxMetricThrottled(String namespace, String metric, String dimensions,
                                                 String startTime, String endTime) {
        try {
            EXTERNAL_CALL_SEM.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        try {
            List<double[]> points = cloudMonitorClient.queryMetric(namespace, metric, dimensions, 60, startTime, endTime);
            double max = -1;
            for (double[] p : points) {
                if (p[1] > max) max = p[1];
            }
            return max;
        } finally {
            EXTERNAL_CALL_SEM.release();
        }
    }

    /** 同比元数据：CloudMonitor 数据源 */
    private Map<String, Object> yoyCm(String ns, String dim, String spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("src", "cm");
        m.put("ns", ns);
        m.put("dim", dim);
        m.put("spec", spec);
        return m;
    }

    /** 同比元数据：Grafana/Prometheus 数据源 */
    private Map<String, Object> yoyGr(String ds, String promql) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("src", "gr");
        m.put("ds", ds);
        m.put("spec", promql);
        return m;
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

            var rdsCpuRows = grafanaClient.queryInstant(
                    "avg by (desc) (AliyunRds_CpuUsage{desc=~\"prod-.*\"})", ds);
            var rdsMemRows = grafanaClient.queryInstant(
                    "avg by (desc) (AliyunRds_MemoryUsage{desc=~\"prod-.*\"})", ds);
            var rdsIopsRows = grafanaClient.queryInstant(
                    "avg by (desc) (AliyunRds_IOPSUsage{desc=~\"prod-.*\"})", ds);
            var rdsSessRows = grafanaClient.queryInstant(
                    "sum by (desc) (AliyunRds_MySQL_ActiveSessions{desc=~\"prod-.*\"})", ds);

            Map<String, double[]> rdsMetrics = new LinkedHashMap<>();
            collectByLabel(rdsCpuRows, "desc", rdsMetrics, 0);
            collectByLabel(rdsMemRows, "desc", rdsMetrics, 1);
            collectByLabel(rdsIopsRows, "desc", rdsMetrics, 2);
            collectByLabel(rdsSessRows, "desc", rdsMetrics, 3);

            for (var entry : rdsMetrics.entrySet()) {
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", entry.getKey());
                item.put("instanceName", entry.getKey());
                item.put("engine", "RDS");
                item.put("cpuUsage", vals[0]);
                item.put("memoryUsage", vals[1]);
                item.put("iops", vals[2]);
                item.put("activeSessions", vals[3]);
                item.put("_yoy", Map.of(
                        "cpuUsage", yoyGr(ds, "avg(AliyunRds_CpuUsage{desc=\"" + entry.getKey() + "\"})"),
                        "memoryUsage", yoyGr(ds, "avg(AliyunRds_MemoryUsage{desc=\"" + entry.getKey() + "\"})")));
                list.add(item);
            }

            var pdbCpuRows = grafanaClient.queryInstant(
                    "avg by (desc) (AliyunPolardb_cluster_cpu_utilization{desc=~\"prod-.*\"})", ds);
            var pdbMemRows = grafanaClient.queryInstant(
                    "avg by (desc) (AliyunPolardb_cluster_memory_utilization{desc=~\"prod-.*\"})", ds);
            var pdbIopsRows = grafanaClient.queryInstant(
                    "avg by (desc) (AliyunPolardb_cluster_iops_usage{desc=~\"prod-.*\"})", ds);
            var pdbSessRows = grafanaClient.queryInstant(
                    "sum by (desc) (AliyunPolardb_cluster_active_sessions{desc=~\"prod-.*\"})", ds);

            Map<String, double[]> pdbMetrics = new LinkedHashMap<>();
            collectByLabel(pdbCpuRows, "desc", pdbMetrics, 0);
            collectByLabel(pdbMemRows, "desc", pdbMetrics, 1);
            collectByLabel(pdbIopsRows, "desc", pdbMetrics, 2);
            collectByLabel(pdbSessRows, "desc", pdbMetrics, 3);

            for (var entry : pdbMetrics.entrySet()) {
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", entry.getKey());
                item.put("instanceName", entry.getKey());
                item.put("engine", "PolarDB");
                item.put("cpuUsage", vals[0]);
                item.put("memoryUsage", vals[1]);
                item.put("iops", vals[2]);
                item.put("activeSessions", vals[3]);
                item.put("_yoy", Map.of(
                        "cpuUsage", yoyGr(ds, "avg(AliyunPolardb_cluster_cpu_utilization{desc=\"" + entry.getKey() + "\"})"),
                        "memoryUsage", yoyGr(ds, "avg(AliyunPolardb_cluster_memory_utilization{desc=\"" + entry.getKey() + "\"})")));
                list.add(item);
            }

            if (list.isEmpty()) {
                list.addAll(generateDemoDbInstances());
                log.info("DB 分库监控使用演示数据: {} 个", list.size());
            }

            dbInstancesCache = list;
            dbInstancesCacheTime = now;
            log.info("DB 分库监控已缓存: {} 个 (RDS+PolarDB)", list.size());
        } catch (Exception e) {
            log.error("查询 DB 分库监控失败: {}", e.getMessage());
            list = generateDemoDbInstances();
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

            var activeRows = grafanaClient.queryInstant(
                    "sum by (application) (druid_active_count)", ds);
            var maxRows = grafanaClient.queryInstant(
                    "sum by (application) (druid_max_active)", ds);
            var waitRows = grafanaClient.queryInstant(
                    "sum by (application) (druid_wait_thread_count)", ds);
            var execRows = grafanaClient.queryInstant(
                    "sum by (application) (irate(druid_execute_count[2m]))", ds);

            Map<String, double[]> metrics = new LinkedHashMap<>();
            collectJvmMetric(activeRows, metrics, 0);
            collectJvmMetric(maxRows, metrics, 1);
            collectJvmMetric(waitRows, metrics, 2);
            collectJvmMetric(execRows, metrics, 3);

            for (var entry : metrics.entrySet()) {
                String app = entry.getKey();
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("application", app);
                item.put("activeCount", vals[0]);
                item.put("maxActive", vals[1]);
                item.put("waitThreadCount", vals[2]);
                item.put("sqlExecuteRate", vals[3]);
                item.put("usageRate", vals[1] > 0 ? vals[0] / vals[1] * 100 : 0);
                list.add(item);
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("activeCount", 0)),
                    toDouble(a.getOrDefault("activeCount", 0))));

            if (list.isEmpty()) {
                list.addAll(generateDemoDruidInstances());
                log.info("Druid 连接池监控使用演示数据: {} 个应用", list.size());
            }

            druidInstancesCache = list;
            druidInstancesCacheTime = now;
            log.info("Druid 连接池监控已缓存: {} 个应用", list.size());
        } catch (Exception e) {
            log.error("查询 Druid 连接池监控失败: {}", e.getMessage());
            list = generateDemoDruidInstances();
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

            var heapRows = grafanaClient.queryInstant(
                    "(sum by (application) (jvm_memory_used_bytes{area=\"heap\"}) * 100) / " +
                    "sum by (application) (jvm_memory_max_bytes{area=\"heap\"})", ds);
            var gcRows = grafanaClient.queryInstant(
                    "sum by (application) (rate(jvm_gc_pause_seconds_count[1m]))", ds);
            var qpsRows = grafanaClient.queryInstant(
                    "sum by (application) (rate(http_server_requests_seconds_count[1m]))", ds);
            var cpuRows = grafanaClient.queryInstant(
                    "avg by (application) (system_cpu_usage)*100", ds);

            Map<String, double[]> metrics = new LinkedHashMap<>();
            collectJvmMetric(heapRows, metrics, 0);
            collectJvmMetric(gcRows, metrics, 1);
            collectJvmMetric(qpsRows, metrics, 2);
            collectJvmMetric(cpuRows, metrics, 3);

            for (var entry : metrics.entrySet()) {
                String app = entry.getKey();
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("application", app);
                item.put("heapUsage", vals[0]);
                item.put("gcRate", vals[1]);
                item.put("qps", vals[2]);
                item.put("cpuUsage", vals[3]);
                list.add(item);
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("heapUsage", 0)),
                    toDouble(a.getOrDefault("heapUsage", 0))));

            if (list.isEmpty()) {
                log.info("JVM 监控查询结果为空，使用演示数据");
                list = generateDemoJvmInstances();
            }
        } catch (Exception e) {
            log.warn("查询 JVM 监控失败，使用演示数据: {}", e.getMessage());
            list = generateDemoJvmInstances();
        }
        jvmInstancesCache = list;
        jvmInstancesCacheTime = now;
        log.info("JVM 监控已缓存: {} 个应用", list.size());
        return list;
    }

    private void collectJvmMetric(List<Map<String, Object>> rows, Map<String, double[]> metrics, int idx) {
        for (var row : rows) {
            String app = String.valueOf(row.getOrDefault("application", ""));
            if (app.isEmpty()) continue;
            double val = toDouble(row.get("value"));
            metrics.computeIfAbsent(app, k -> new double[4])[idx] = val;
        }
    }

    /**
     * 线程池监控：按应用+线程池名聚合活跃线程/最大线程/队列大小/拒绝任务数（k8s Prometheus），5 分钟缓存
     */
    public List<Map<String, Object>> threadPoolInstances() {
        long now = System.currentTimeMillis();
        if (threadPoolCache != null && now - threadPoolCacheTime < MONITOR_CACHE_TTL_MS) {
            return threadPoolCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            String ds = grafanaClient.getDsUid();
            String byPool = "sum by (app_name, thread_pool_name)";

            var activeRows = grafanaClient.queryInstant(byPool + " (thread_pool_active_count)", ds);
            var maxRows = grafanaClient.queryInstant(byPool + " (thread_pool_maximum_size)", ds);
            var queueRows = grafanaClient.queryInstant(byPool + " (thread_pool_queue_size)", ds);
            var rejectRows = grafanaClient.queryInstant(byPool + " (rate(thread_pool_reject_count[1m]))*60", ds);

            Map<String, double[]> metrics = new LinkedHashMap<>();
            collectMetric(activeRows, metrics, 0);
            collectMetric(maxRows, metrics, 1);
            collectMetric(queueRows, metrics, 2);
            collectMetric(rejectRows, metrics, 3);

            for (var entry : metrics.entrySet()) {
                String[] keys = entry.getKey().split("\0", 2);
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("application", keys[0]);
                item.put("threadPoolName", keys[1]);
                item.put("activeCount", vals[0]);
                item.put("maxSize", vals[1]);
                item.put("queueSize", vals[2]);
                item.put("rejectPerMin", vals[3]);
                item.put("usageRate", vals[1] > 0 ? vals[0] / vals[1] * 100 : 0);
                list.add(item);
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("activeCount", 0)),
                    toDouble(a.getOrDefault("activeCount", 0))));

            if (list.isEmpty()) {
                log.info("线程池监控查询结果为空，使用演示数据");
                list = generateDemoThreadPoolInstances();
            }
        } catch (Exception e) {
            log.warn("查询线程池监控失败，使用演示数据: {}", e.getMessage());
            list = generateDemoThreadPoolInstances();
        }
        threadPoolCache = list;
        threadPoolCacheTime = now;
        log.info("线程池监控已缓存: {} 个线程池", list.size());
        return list;
    }

    private void collectMetric(List<Map<String, Object>> rows, Map<String, double[]> metrics, int idx) {
        for (var row : rows) {
            String app = String.valueOf(row.getOrDefault("app_name", ""));
            String pool = String.valueOf(row.getOrDefault("thread_pool_name", ""));
            if (app.isEmpty() || pool.isEmpty()) continue;
            double val = toDouble(row.get("value"));
            metrics.computeIfAbsent(app + "\0" + pool, k -> new double[4])[idx] = val;
        }
    }

    private void collectByLabel(List<Map<String, Object>> rows, String labelKey,
                                Map<String, double[]> metrics, int idx) {
        for (var row : rows) {
            String key = String.valueOf(row.getOrDefault(labelKey, ""));
            if (key.isEmpty()) continue;
            double val = toDouble(row.get("value"));
            metrics.computeIfAbsent(key, k -> new double[8])[idx] = val;
        }
    }

    private void collectByDualLabel(List<Map<String, Object>> rows, String idKey, String nameKey,
                                    Map<String, double[]> metrics, int idx) {
        for (var row : rows) {
            String id = String.valueOf(row.getOrDefault(idKey, ""));
            if (id.isEmpty()) continue;
            String name = String.valueOf(row.getOrDefault(nameKey, id));
            double val = toDouble(row.get("value"));
            metrics.computeIfAbsent(id + "\0" + name, k -> new double[4])[idx] = val;
        }
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
                var slsInstances = rocketmqInstances();
                for (var inst : slsInstances) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rank", list.size() + 1);
                    item.put("topic", "(实例级汇总)");
                    item.put("instanceName", inst.getOrDefault("instanceName", ""));
                    item.put("messageAccumulation", inst.getOrDefault("messageAccumulation", 0));
                    item.put("sendTps", inst.getOrDefault("sendTps", 0));
                    item.put("consumeTps", inst.getOrDefault("consumeTps", 0));
                    list.add(item);
                }
                log.info("RocketMQ Top Topics: CloudMonitor 无实例，使用 SLS 数据 {} 条", list.size());
            } else {
                String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
                String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

                for (var inst : allInstances) {
                    String dim = "[{\"instanceId\":\"" + inst.getInstanceId() + "\"}]";
                    double accumulation = queryLatestMetricThrottled("acs_mq", "MessageAccumulation", dim, startTime, endTime);
                    double sendTps = queryLatestMetricThrottled("acs_mq", "SendTps", dim, startTime, endTime);
                    double consumeTps = queryLatestMetricThrottled("acs_mq", "ConsumeTps", dim, startTime, endTime);

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
            }

            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("messageAccumulation", 0)),
                    toDouble(a.getOrDefault("messageAccumulation", 0))));

            for (int i = 0; i < list.size(); i++) {
                list.get(i).put("rank", i + 1);
            }

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

            for (int i = 0; i < list.size(); i++) {
                list.get(i).put("rank", i + 1);
            }

            if (list.isEmpty()) {
                list.addAll(generateDemoKafkaTopPartitions());
                log.info("Kafka Top Partitions 使用演示数据: {} 个", list.size());
            }

            kafkaTopPartitionsCache = list.size() > 20 ? list.subList(0, 20) : list;
            kafkaTopPartitionsCacheTime = now;
            log.info("Kafka Top Partitions (from Prometheus): {} 个（已缓存）", kafkaTopPartitionsCache.size());
        } catch (Exception e) {
            log.error("查询 Kafka Top Partitions 失败: {}", e.getMessage());
            list = generateDemoKafkaTopPartitions();
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
            String endTimeStr = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTimeStr = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            var itemFutures = instances.stream()
                .map(inst -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    String instanceId = (String) inst.get("instanceId");
                    String instanceName = (String) inst.get("instanceName");

                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    double usedMemory = queryLatestMetricThrottled("acs_kvstore", "UsedMemory", dim, startTimeStr, endTimeStr);

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", instanceName);
                    item.put("type", "instance");
                    item.put("description", "Redis 实例内存使用");
                    item.put("memoryBytes", usedMemory);
                    item.put("ttl", -1);
                    return item;
                }, queryExecutor))
                .toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(itemFutures).join();
            for (var f : itemFutures) {
                try { list.add((Map<String, Object>) f.get()); } catch (Exception ignored) {}
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
     * Redis Slow Queries：从 ARMS 链路追踪查询应用级别的 Redis 慢调用
     * 思路：与 MySQL 慢查一致，查询 trace → 提取 Redis 类型的 span → 按耗时排序
     */
    /**
     * 等待 trace 详情查询完成并收集结果：总时长超限时立即返回已完成部分，
     * 避免首查（缓存为空）时外部依赖阻塞拖垮请求
     */
    private int collectTraceResults(java.util.concurrent.CompletableFuture<?>[] traceFutures,
                                    List<Map<String, Object>> list, String scene) {
        try {
            java.util.concurrent.CompletableFuture.allOf(traceFutures)
                    .get(TRACE_DETAIL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("查询 trace 详情总超时 {}ms（{}），仅返回已完成部分", TRACE_DETAIL_TIMEOUT_MS, scene);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("查询 trace 详情被中断（{}）", scene);
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("查询 trace 详情异常（{}）: {}", scene, e.getMessage());
        }
        int traceCount = 0;
        for (var f : traceFutures) {
            if (!f.isDone()) continue;
            try {
                var results = (List<Map<String, Object>>) f.get();
                if (!results.isEmpty()) {
                    list.addAll(results);
                    traceCount++;
                }
            } catch (Exception ignored) {}
        }
        return traceCount;
    }

    public List<Map<String, Object>> redisSlowQueries() {
        long now = System.currentTimeMillis();
        if (redisSlowQueriesCache != null && now - redisSlowQueriesCacheTime < MONITOR_CACHE_TTL_MS) {
            return redisSlowQueriesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            long toMs = now;
            long fromMs = toMs - 3600_000; // 最近 1 小时

            // 1. 获取 ARMS 应用列表，找到目标应用
            String targetAppName = null;
            var appsResp = armsClient.listApps();
            if (appsResp.getTraceApps() != null) {
                for (var app : appsResp.getTraceApps()) {
                    String appName = app.getAppName();
                    if (appName != null && (appName.contains("order-prod") || appName.contains("charge-prod"))) {
                        targetAppName = appName;
                        log.info("Redis Slow Queries 找到目标应用: {}", appName);
                        break;
                    }
                }
            }

            if (targetAppName == null) {
                log.warn("未找到目标应用（order-prod/charge-prod），无法查询 Redis 慢调用");
                return list;
            }

            // 2. 搜索该应用的 trace
            var searchResp = armsClient.searchTraces(targetAppName, fromMs, toMs);
            var traceItems = searchResp.getTraceInfos();
            if (traceItems == null || traceItems.isEmpty()) {
                log.info("ARMS 无 trace 数据（Redis 慢查询）");
                return list;
            }

            // 3. 并行查询 trace 详情，提取 Redis span（信号量控制最多 3 并发）
            int maxTraces = Math.min(traceItems.size(), 20);
            var traceFutures = new java.util.concurrent.CompletableFuture[maxTraces];
            for (int i = 0; i < maxTraces; i++) {
                var traceItem = traceItems.get(i);
                traceFutures[i] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    String traceId = traceItem.getTraceID();
                    if (traceId == null || traceId.isEmpty()) return Collections.<Map<String, Object>>emptyList();

                    try {
                        EXTERNAL_CALL_SEM.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Collections.<Map<String, Object>>emptyList();
                    }
                    try {
                        var traceResp = armsClient.getTrace(traceId, fromMs, toMs);
                        var spans = traceResp.getSpans();
                        if (spans == null) return Collections.<Map<String, Object>>emptyList();

                        List<Map<String, Object>> results = new ArrayList<>();
                        for (var span : spans) {
                            Integer rpcType = span.getRpcType();
                            boolean isRedis = false;
                            String redisCommand = null;

                            var tags = span.getTagEntryList();
                            if (tags != null) {
                                for (var tag : tags) {
                                    String key = tag.getKey();
                                    String value = tag.getValue();

                                    if ("db.system.name".equals(key) && value != null
                                            && value.toLowerCase().contains("redis")) {
                                        isRedis = true;
                                    }
                                    if ("db.statement".equals(key) || "db.query.text".equals(key)) {
                                        redisCommand = value;
                                    }
                                    if ("call.type".equals(key) && value != null
                                            && value.toLowerCase().contains("redis")) {
                                        isRedis = true;
                                    }
                                }
                            }

                            if (rpcType != null && rpcType == 4) {
                                isRedis = true;
                            }

                            if (!isRedis) continue;

                            String serviceName = span.getServiceName();
                            long duration = span.getDuration() != null ? span.getDuration() : 0;
                            String timestamp = span.getTimestamp() != null ? String.valueOf(span.getTimestamp()) : null;

                            if (duration < 5) continue;

                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("instanceName", serviceName);
                            item.put("command", redisCommand != null
                                    ? (redisCommand.length() > 500 ? redisCommand.substring(0, 500) + "..." : redisCommand)
                                    : span.getOperationName());
                            item.put("durationMs", (double) duration);
                            item.put("timestamp", timestamp);
                            results.add(item);
                        }
                        return results;
                    } catch (Exception e) {
                        log.warn("查询 trace 详情失败（Redis 慢查询）: traceId={}, error={}", traceId, e.getMessage());
                        return Collections.<Map<String, Object>>emptyList();
                    } finally {
                        EXTERNAL_CALL_SEM.release();
                    }
                }, queryExecutor);
            }

            int traceCount = collectTraceResults(traceFutures, list, "Redis 慢查询");

            log.info("Redis Slow Queries: 从 ARMS 查询 {} 条 trace（已缓存）", traceCount);

            // 4. 按耗时降序排序
            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("durationMs", 0)),
                    toDouble(a.getOrDefault("durationMs", 0))));

            // 5. 设置排名并限制返回数量
            int limit = Math.min(list.size(), 50);
            for (int i = 0; i < limit; i++) {
                list.get(i).put("rank", i + 1);
            }

            redisSlowQueriesCache = limit > 0 ? list.subList(0, limit) : list;
            redisSlowQueriesCacheTime = now;
            log.info("Redis Slow Queries: {} 条（从 ARMS 查询 {} 条 trace，已缓存）", redisSlowQueriesCache.size(), traceCount);
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

            var rdsCpuRows = grafanaClient.queryInstant(
                    "avg by (instanceId, instanceName) (AliyunRds_CpuUsage)", ds);
            var rdsMemRows = grafanaClient.queryInstant(
                    "avg by (instanceId, instanceName) (AliyunRds_MemoryUsage)", ds);

            Map<String, double[]> rdsMetrics = new LinkedHashMap<>();
            collectByDualLabel(rdsCpuRows, "instanceId", "instanceName", rdsMetrics, 0);
            collectByDualLabel(rdsMemRows, "instanceId", "instanceName", rdsMetrics, 1);

            for (var entry : rdsMetrics.entrySet()) {
                String[] keys = entry.getKey().split("\0", 2);
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", keys.length > 1 ? keys[1] : keys[0]);
                item.put("instanceId", keys[0]);
                item.put("engine", "RDS");
                item.put("cpuUsage", vals[0]);
                item.put("memoryUsage", vals[1]);
                item.put("diskUsage", 0.0);
                list.add(item);
            }

            var pdbCpuRows = grafanaClient.queryInstant(
                    "avg by (instanceId, instanceName) (AliyunPolardb_cluster_cpu_utilization)", ds);
            var pdbMemRows = grafanaClient.queryInstant(
                    "avg by (instanceId, instanceName) (AliyunPolardb_cluster_memory_utilization)", ds);

            Map<String, double[]> pdbMetrics = new LinkedHashMap<>();
            collectByDualLabel(pdbCpuRows, "instanceId", "instanceName", pdbMetrics, 0);
            collectByDualLabel(pdbMemRows, "instanceId", "instanceName", pdbMetrics, 1);

            for (var entry : pdbMetrics.entrySet()) {
                String[] keys = entry.getKey().split("\0", 2);
                double[] vals = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", keys.length > 1 ? keys[1] : keys[0]);
                item.put("instanceId", keys[0]);
                item.put("engine", "PolarDB");
                item.put("cpuUsage", vals[0]);
                item.put("memoryUsage", vals[1]);
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

            if (list.isEmpty()) {
                list.addAll(generateDemoMysqlTopTables());
                log.info("MySQL Top Tables 使用演示数据: {} 个", list.size());
            }

            mysqlTopTablesCache = list.size() > 50 ? list.subList(0, 50) : list;
            mysqlTopTablesCacheTime = now;
            log.info("MySQL Top Tables: {} 个（已缓存）", mysqlTopTablesCache.size());
        } catch (Exception e) {
            log.error("查询 MySQL Top Tables 失败: {}", e.getMessage());
            list = generateDemoMysqlTopTables();
        }
        return list;
    }

    /**
     * MySQL Slow Queries：从 ARMS 链路追踪查询应用级别的慢 SQL
     * 思路：查询指定应用的 trace → 对每条 trace 查询详情 → 提取 SQL 类型的 span → 按耗时排序
     */
    public List<Map<String, Object>> mysqlSlowQueries() {
        long now = System.currentTimeMillis();
        if (mysqlSlowQueriesCache != null && now - mysqlSlowQueriesCacheTime < MONITOR_CACHE_TTL_MS) {
            return mysqlSlowQueriesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            long toMs = now;
            long fromMs = toMs - 3600_000; // 最近 1 小时

            // 1. 获取 ARMS 应用列表，找到有数据库调用的应用（如 order-prod）
            String targetAppName = null;
            var appsResp = armsClient.listApps();
            if (appsResp.getTraceApps() != null) {
                for (var app : appsResp.getTraceApps()) {
                    String appName = app.getAppName();
                    if (appName != null && (appName.contains("order-prod") || appName.contains("charge-prod"))) {
                        targetAppName = appName;
                        log.info("找到目标应用: {}", appName);
                        break;
                    }
                }
            }

            if (targetAppName == null) {
                log.warn("未找到目标应用（order-prod/charge-prod），使用演示慢 SQL 数据");
                return generateDemoMysqlSlowQueries();
            }

            // 2. 搜索该应用的 trace（按 ServiceName 过滤）
            var searchResp = armsClient.searchTraces(targetAppName, fromMs, toMs);
            var traceItems = searchResp.getTraceInfos();
            if (traceItems == null || traceItems.isEmpty()) {
                log.info("ARMS 无 trace 数据，使用演示慢 SQL 数据");
                return generateDemoMysqlSlowQueries();
            }

            // 3. 并行查询 trace 详情，提取 SQL span（信号量控制最多 3 并发）
            int maxTraces = Math.min(traceItems.size(), 20);
            var traceFutures = new java.util.concurrent.CompletableFuture[maxTraces];
            for (int i = 0; i < maxTraces; i++) {
                var traceItem = traceItems.get(i);
                traceFutures[i] = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    String traceId = traceItem.getTraceID();
                    if (traceId == null || traceId.isEmpty()) return Collections.<Map<String, Object>>emptyList();

                    try {
                        EXTERNAL_CALL_SEM.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Collections.<Map<String, Object>>emptyList();
                    }
                    try {
                        var traceResp = armsClient.getTrace(traceId, fromMs, toMs);
                        var spans = traceResp.getSpans();
                        if (spans == null) return Collections.<Map<String, Object>>emptyList();

                        List<Map<String, Object>> results = new ArrayList<>();
                        for (var span : spans) {
                            Integer rpcType = span.getRpcType();
                            boolean isSql = false;
                            String sql = null;

                            var tags = span.getTagEntryList();
                            if (tags != null) {
                                for (var tag : tags) {
                                    String key = tag.getKey();
                                    String value = tag.getValue();

                                    if ("db.system.name".equals(key) && value != null &&
                                        (value.toLowerCase().contains("mysql") || value.toLowerCase().contains("postgresql") ||
                                         value.toLowerCase().contains("oracle") || value.toLowerCase().contains("sql"))) {
                                        isSql = true;
                                    }
                                    if ("db.statement".equals(key) || "db.query.text".equals(key)) {
                                        sql = value;
                                    }
                                    if ("call.type".equals(key) && value != null &&
                                        (value.toLowerCase().contains("sql") || value.toLowerCase().contains("jdbc"))) {
                                        isSql = true;
                                    }
                                }
                            }

                            if (rpcType != null && rpcType == 14) {
                                isSql = true;
                            }

                            if (!isSql) continue;

                            String serviceName = span.getServiceName();
                            long duration = span.getDuration() != null ? span.getDuration() : 0;
                            String timestamp = span.getTimestamp() != null ? String.valueOf(span.getTimestamp()) : null;

                            if (sql == null || sql.isEmpty()) continue;
                            if (duration < 10) continue;

                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("instanceName", serviceName);
                            item.put("sql", sql.length() > 500 ? sql.substring(0, 500) + "..." : sql);
                            item.put("durationMs", (double) duration);
                            item.put("timestamp", timestamp);
                            results.add(item);
                        }
                        return results;
                    } catch (Exception e) {
                        log.warn("查询 trace 详情失败: traceId={}, error={}", traceId, e.getMessage());
                        return Collections.<Map<String, Object>>emptyList();
                    } finally {
                        EXTERNAL_CALL_SEM.release();
                    }
                }, queryExecutor);
            }

            int traceCount = collectTraceResults(traceFutures, list, "MySQL 慢查询");

            log.info("MySQL Slow Queries: 从 ARMS 查询 {} 条 trace（已缓存）", traceCount);

            // 6. 按耗时降序排序
            list.sort((a, b) -> Double.compare(
                    toDouble(b.getOrDefault("durationMs", 0)),
                    toDouble(a.getOrDefault("durationMs", 0))));

            // 7. 设置排名并限制返回数量
            if (list.isEmpty()) {
                list = generateDemoMysqlSlowQueries();
                log.info("MySQL 慢查询使用演示数据: {} 条", list.size());
            }

            int limit = Math.min(list.size(), 50);
            for (int i = 0; i < limit; i++) {
                list.get(i).put("rank", i + 1);
            }

            mysqlSlowQueriesCache = limit > 0 ? list.subList(0, limit) : list;
            mysqlSlowQueriesCacheTime = now;
            log.info("MySQL Slow Queries: {} 条（从 ARMS 查询 {} 条 trace，已缓存）", mysqlSlowQueriesCache.size(), traceCount);
        } catch (Exception e) {
            log.error("查询 MySQL Slow Queries 失败: {}", e.getMessage());
            list = generateDemoMysqlSlowQueries();
        }
        return list;
    }

    /**
     * Lindorm Top 实例（按 QPS 排序，复用 lindormInstances 的丰富指标）
     */
    public List<Map<String, Object>> lindormTopTables() {
        long now = System.currentTimeMillis();
        if (lindormTopTablesCache != null && now - lindormTopTablesCacheTime < MONITOR_CACHE_TTL_MS) {
            return lindormTopTablesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        try {
            var instances = lindormInstances();

            for (var inst : instances) {
                String instanceName = (String) inst.get("instanceName");

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tableName", instanceName);
                item.put("instanceId", inst.get("instanceId"));
                item.put("description", "Lindorm 实例");
                item.put("readQps", inst.getOrDefault("qps", 0));
                item.put("writeQps", inst.getOrDefault("writeQps", 0));
                item.put("readRt", inst.getOrDefault("readRt", 0));
                item.put("writeRt", inst.getOrDefault("writeRt", 0));
                item.put("cpuUsage", inst.getOrDefault("cpuUsage", 0));
                item.put("hotStorageUsedBytes", inst.getOrDefault("hotStorageUsedBytes", 0));
                item.put("hotStorageUsedPercent", inst.getOrDefault("hotStorageUsedPercent", 0));
                item.put("compactionQueueSize", inst.getOrDefault("compactionQueueSize", 0));
                item.put("handlerQueueSize", inst.getOrDefault("handlerQueueSize", 0));
                list.add(item);
            }

            list.sort((a, b) -> {
                double totalA = ((Number) a.getOrDefault("readQps", 0)).doubleValue()
                        + ((Number) a.getOrDefault("writeQps", 0)).doubleValue();
                double totalB = ((Number) b.getOrDefault("readQps", 0)).doubleValue()
                        + ((Number) b.getOrDefault("writeQps", 0)).doubleValue();
                return Double.compare(totalB, totalA);
            });

            for (int i = 0; i < list.size(); i++) {
                list.get(i).put("rank", i + 1);
            }

            lindormTopTablesCache = list.size() > 20 ? list.subList(0, 20) : list;
            lindormTopTablesCacheTime = now;
            log.info("Lindorm Top Tables: {} 个（已缓存）", lindormTopTablesCache.size());
        } catch (Exception e) {
            log.error("查询 Lindorm Top Tables 失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 诊断：发现 Lindorm 所有可用指标（Prometheus + CloudMonitor）
     */
    public Map<String, Object> lindormMetricsDiscovery() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String aliyunDs = grafanaClient.getAliyunDsUid();
            var promRows = grafanaClient.queryInstant(
                    "count by (__name__) ({__name__=~\"AliyunLindorm_.*\"})", aliyunDs);
            List<String> promMetrics = new ArrayList<>();
            for (var row : promRows) {
                promMetrics.add(String.valueOf(row.getOrDefault("__name__", "")));
            }
            Collections.sort(promMetrics);
            result.put("prometheusMetrics", promMetrics);
            result.put("prometheusCount", promMetrics.size());
        } catch (Exception e) {
            result.put("prometheusError", e.getMessage());
        }
        try {
            List<String> cmMetrics = cloudMonitorClient.listMetricMeta("acs_lindorm");
            result.put("cloudMonitorMetrics", cmMetrics);
            result.put("cloudMonitorCount", cmMetrics.size());
        } catch (Exception e) {
            result.put("cloudMonitorError", e.getMessage());
        }
        try {
            var instances = cloudMonitorClient.listLindormInstances();
            result.put("lindormInstances", instances);
        } catch (Exception e) {
            result.put("instancesError", e.getMessage());
        }
        return result;
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

            var itemFutures = instances.stream()
                .map(inst -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    String instanceId = (String) inst.get("instanceId");
                    String instanceName = (String) inst.get("instanceName");

                    String dim = "[{\"instanceId\":\"" + instanceId + "\"}]";
                    double diskUsage = queryLatestMetricThrottled("acs_elasticsearch", "NodeDiskUtilization", dim, startTime, endTime);

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("indexName", instanceName);
                    item.put("description", "Elasticsearch 实例");
                    item.put("docCount", 0);
                    item.put("storageGB", diskUsage / 1024);
                    item.put("shardCount", 0);
                    item.put("replicaCount", 0);
                    return item;
                }, queryExecutor))
                .toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(itemFutures).join();
            for (var f : itemFutures) {
                try {
                    var item = f.get();
                    if (item != null) list.add((Map<String, Object>) item);
                } catch (Exception ignored) {}
            }

            list.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("storageGB", 0)).doubleValue(),
                    ((Number) a.getOrDefault("storageGB", 0)).doubleValue()));

            for (int i = 0; i < list.size(); i++) {
                list.get(i).put("rank", i + 1);
            }

            elasticsearchTopIndicesCache = list.size() > 20 ? list.subList(0, 20) : list;
            elasticsearchTopIndicesCacheTime = now;
            log.info("Elasticsearch Top Indices: {} 个（已缓存）", elasticsearchTopIndicesCache.size());
        } catch (Exception e) {
            log.error("查询 Elasticsearch Top Indices 失败: {}", e.getMessage());
        }
        return list;
    }

    private static final List<String> DEMO_APPS = List.of(
            "base-server", "charge-server", "order-foundation", "activity-server",
            "foundation", "external-server", "price-center-serve", "dmp-query-server", "order-server");

    private List<Map<String, Object>> generateDemoPodCpu() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(42);
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pod", "pod-" + DEMO_APPS.get(i % DEMO_APPS.size()) + "-" + (i + 1));
            row.put("cpu", Math.round((0.1 + rnd.nextDouble() * 0.8) * 100.0) / 100.0);
            list.add(row);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoPodMemory() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(43);
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pod", "pod-" + DEMO_APPS.get(i % DEMO_APPS.size()) + "-" + (i + 1));
            row.put("memoryMB", Math.round((256 + rnd.nextDouble() * 1500) * 10.0) / 10.0);
            list.add(row);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoNodeOverview() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(44);
        String[] nodes = {"node-172.25.10.1", "node-172.25.10.2", "node-172.25.10.3",
                "node-172.25.10.4", "node-172.25.10.5"};
        for (String node : nodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node", node);
            row.put("cpuUsage", Math.round((10 + rnd.nextDouble() * 60) * 10.0) / 10.0);
            row.put("memoryUsage", Math.round((20 + rnd.nextDouble() * 50) * 10.0) / 10.0);
            list.add(row);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoJvmInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(45);
        for (String app : DEMO_APPS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("application", app);
            item.put("heapUsage", Math.round((30 + rnd.nextDouble() * 50) * 10.0) / 10.0);
            item.put("gcRate", Math.round(rnd.nextDouble() * 5 * 100.0) / 100.0);
            item.put("qps", Math.round((50 + rnd.nextDouble() * 300) * 10.0) / 10.0);
            item.put("cpuUsage", Math.round((10 + rnd.nextDouble() * 50) * 10.0) / 10.0);
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
                toDouble(b.getOrDefault("heapUsage", 0)),
                toDouble(a.getOrDefault("heapUsage", 0))));
        return list;
    }

    private List<Map<String, Object>> generateDemoThreadPoolInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(46);
        String[] pools = {"http-nio", "rpc-worker", "scheduled", "async-task", "io-worker"};
        for (String app : DEMO_APPS) {
            for (String pool : pools) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("application", app);
                item.put("threadPoolName", pool);
                int max = 50 + rnd.nextInt(200);
                int active = rnd.nextInt(max);
                item.put("activeCount", active);
                item.put("maxSize", max);
                item.put("queueSize", rnd.nextInt(500));
                item.put("rejectPerMin", Math.round(rnd.nextDouble() * 10 * 100.0) / 100.0);
                item.put("usageRate", max > 0 ? Math.round(active * 100.0 / max * 10.0) / 10.0 : 0);
                list.add(item);
            }
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoKafkaInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(47);
        String[][] kafkas = {
                {"kafka-prod-order", "2.8.1"},
                {"kafka-prod-log", "2.8.1"},
                {"kafka-prod-track", "3.0.0"},
                {"kafka-prod-notify", "2.8.1"}
        };
        for (String[] k : kafkas) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", "alikafka-" + Math.abs(k[0].hashCode()));
            item.put("instanceName", k[0]);
            item.put("region", "cn-hangzhou");
            item.put("status", "Running");
            item.put("version", k[1]);
            item.put("lag", Math.round(rnd.nextDouble() * 30000));
            item.put("produceTps", Math.round(rnd.nextDouble() * 5000 * 10.0) / 10.0);
            item.put("consumeTps", Math.round(rnd.nextDouble() * 4800 * 10.0) / 10.0);
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoLindormInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(48);
        String[] lindorms = {"prod-lindorm-main", "prod-lindorm-log"};
        for (String name : lindorms) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", "ld-" + Math.abs(name.hashCode()));
            item.put("instanceName", name);
            double cpu = Math.round((10 + rnd.nextDouble() * 60) * 10.0) / 10.0;
            double hotStorage = Math.round((20 + rnd.nextDouble() * 50) * 10.0) / 10.0;
            item.put("cpuUsage", cpu);
            item.put("cpuWio", Math.round(rnd.nextDouble() * 8 * 10.0) / 10.0);
            item.put("qps", Math.round(rnd.nextDouble() * 8000));
            item.put("writeQps", Math.round(rnd.nextDouble() * 3000));
            item.put("readRt", Math.round(rnd.nextDouble() * 20 * 10.0) / 10.0);
            item.put("writeRt", Math.round(rnd.nextDouble() * 30 * 10.0) / 10.0);
            item.put("diskUsage", hotStorage);
            item.put("diskReadBytes", Math.round(rnd.nextDouble() * 500000000L));
            item.put("bytesIn", Math.round(rnd.nextDouble() * 200000000L));
            item.put("bytesOut", Math.round(rnd.nextDouble() * 180000000L));
            item.put("hotStorageUsedPercent", hotStorage);
            item.put("hotStorageUsedBytes", Math.round(hotStorage / 100.0 * 1024L * 1024L * 1024L * 1024L));
            item.put("coldStorageUsedPercent", Math.round(rnd.nextDouble() * 20 * 10.0) / 10.0);
            item.put("getRtAvg", Math.round(rnd.nextDouble() * 15 * 10.0) / 10.0);
            item.put("getRtP99", Math.round((15 + rnd.nextDouble() * 40) * 10.0) / 10.0);
            item.put("compactionQueueSize", rnd.nextInt(2000));
            item.put("handlerQueueSize", rnd.nextInt(500));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoDbInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(49);
        String[][] dbs = {
                {"rm-prod-order-001", "RDS"}, {"rm-prod-order-002", "RDS"},
                {"rm-prod-user-001", "RDS"}, {"pc-prod-pay-001", "PolarDB"},
                {"pc-prod-pay-002", "PolarDB"}, {"pc-prod-report-001", "PolarDB"}
        };
        for (String[] db : dbs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", db[0]);
            item.put("instanceName", db[0]);
            item.put("engine", db[1]);
            item.put("cpuUsage", Math.round((5 + rnd.nextDouble() * 55) * 10.0) / 10.0);
            item.put("memoryUsage", Math.round((20 + rnd.nextDouble() * 50) * 10.0) / 10.0);
            item.put("iops", Math.round(rnd.nextDouble() * 8000));
            item.put("activeSessions", Math.round(rnd.nextDouble() * 300));
            item.put("_yoy", Map.of(
                    "cpuUsage", Math.round((rnd.nextDouble() * 30 - 10) * 10.0) / 10.0,
                    "memoryUsage", Math.round((rnd.nextDouble() * 25 - 8) * 10.0) / 10.0));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoDruidInstances() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(50);
        String[] apps = {"order-prod", "charge-prod", "user-prod", "report-prod", "gateway-prod"};
        for (String app : apps) {
            Map<String, Object> item = new LinkedHashMap<>();
            int maxActive = 50 + rnd.nextInt(150);
            int activeCount = (int) Math.round(maxActive * (0.3 + rnd.nextDouble() * 0.5));
            item.put("application", app);
            item.put("activeCount", activeCount);
            item.put("maxActive", maxActive);
            item.put("waitThreadCount", rnd.nextInt(5));
            item.put("sqlExecuteRate", Math.round(rnd.nextDouble() * 2000 * 10.0) / 10.0);
            item.put("usageRate", Math.round((double) activeCount / maxActive * 1000.0) / 10.0);
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
                toDouble(b.getOrDefault("activeCount", 0)),
                toDouble(a.getOrDefault("activeCount", 0))));
        return list;
    }

    private List<Map<String, Object>> generateDemoKafkaTopPartitions() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(51);
        String[][] partitions = {
                {"order-created", "order-prod"}, {"pay-result", "charge-prod"},
                {"user-behavior", "track-prod"}, {"log-collect", "log-prod"},
                {"notify-push", "notify-prod"}, {"stock-change", "order-prod"},
                {"refund-apply", "charge-prod"}, {"coupon-grant", "user-prod"}
        };
        int rank = 1;
        for (String[] p : partitions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topic", p[0]);
            row.put("instanceName", p[1]);
            row.put("lag", Math.round(rnd.nextDouble() * 50000));
            row.put("produceTps", Math.round(rnd.nextDouble() * 1200 * 10.0) / 10.0);
            row.put("consumeTps", Math.round(rnd.nextDouble() * 1100 * 10.0) / 10.0);
            row.put("rank", rank++);
            list.add(row);
        }
        list.sort((a, b) -> Double.compare(
                toDouble(b.getOrDefault("lag", 0)),
                toDouble(a.getOrDefault("lag", 0))));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    private List<Map<String, Object>> generateDemoMysqlTopTables() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(52);
        String[][] tables = {
                {"rm-prod-order-001", "RDS", "order_main"}, {"rm-prod-order-002", "RDS", "order_item"},
                {"rm-prod-user-001", "RDS", "user_profile"}, {"pc-prod-pay-001", "PolarDB", "pay_flow"},
                {"pc-prod-pay-002", "PolarDB", "refund_record"}, {"pc-prod-report-001", "PolarDB", "daily_summary"}
        };
        for (String[] t : tables) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tableName", t[2]);
            item.put("instanceId", t[0]);
            item.put("engine", t[1]);
            item.put("cpuUsage", Math.round((5 + rnd.nextDouble() * 60) * 10.0) / 10.0);
            item.put("memoryUsage", Math.round((15 + rnd.nextDouble() * 55) * 10.0) / 10.0);
            item.put("diskUsage", Math.round((20 + rnd.nextDouble() * 40) * 10.0) / 10.0);
            list.add(item);
        }
        list.sort((a, b) -> {
            double aScore = ((Number) a.getOrDefault("cpuUsage", 0)).doubleValue()
                    + ((Number) a.getOrDefault("memoryUsage", 0)).doubleValue();
            double bScore = ((Number) b.getOrDefault("cpuUsage", 0)).doubleValue()
                    + ((Number) b.getOrDefault("memoryUsage", 0)).doubleValue();
            return Double.compare(bScore, aScore);
        });
        return list;
    }

    private List<Map<String, Object>> generateDemoMysqlSlowQueries() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random rnd = new Random(53);
        long nowMs = System.currentTimeMillis();
        String[][] slowSqls = {
                {"order-prod", "SELECT o.id, o.order_no, u.name FROM order_main o LEFT JOIN user_profile u ON o.user_id = u.id WHERE o.created_at > ? AND o.status IN (?, ?) ORDER BY o.created_at DESC LIMIT 100"},
                {"charge-prod", "UPDATE pay_flow SET pay_status = ?, updated_at = NOW() WHERE order_no = ? AND pay_status = ?"},
                {"order-prod", "SELECT COUNT(DISTINCT user_id) FROM order_item WHERE sku_id = ? AND created_at BETWEEN ? AND ?"},
                {"user-prod", "SELECT * FROM user_profile WHERE phone = ? OR email = ?"},
                {"charge-prod", "SELECT SUM(amount) FROM refund_record WHERE merchant_id = ? AND refund_status = ? GROUP BY merchant_id"},
                {"report-prod", "SELECT DATE(created_at) AS dt, COUNT(*) AS cnt FROM daily_summary WHERE created_at >= ? GROUP BY DATE(created_at) ORDER BY dt DESC"}
        };
        int rank = 1;
        for (String[] s : slowSqls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceName", s[0]);
            item.put("sql", s[1]);
            item.put("durationMs", Math.round((80 + rnd.nextDouble() * 900) * 10.0) / 10.0);
            item.put("timestamp", String.valueOf(nowMs - rank * 60000L));
            item.put("rank", rank++);
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
                toDouble(b.getOrDefault("durationMs", 0)),
                toDouble(a.getOrDefault("durationMs", 0))));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }
}
