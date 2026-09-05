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
     * 查询 Redis 实例列表 + 每个实例的 CPU/连接数/内存/QPS（仅 prod 环境，带 5 分钟缓存）
     */
    public List<Map<String, Object>> redisInstances() {
        long now = System.currentTimeMillis();
        if (redisInstancesCache != null && now - redisInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            log.debug("Redis 实例使用缓存（{} 个）", redisInstancesCache.size());
            return redisInstancesCache;
        }

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
            redisInstancesCacheTime = now;
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

                    item.put("cpuUsage", queryLatestMetric("acs_rds_dashboard", "Cluster_CpuUsage", dim, startTime, endTime));
                    item.put("connections", queryLatestMetric("acs_rds_dashboard", "Cluster_ConnectionUsage", dim, startTime, endTime));
                    item.put("iops", queryLatestMetric("acs_rds_dashboard", "Cluster_IOPSUsage", dim, startTime, endTime));
                    item.put("diskUsage", queryLatestMetric("acs_rds_dashboard", "Cluster_DiskUsage", dim, startTime, endTime));
                    return item;
                })
            ).toArray(java.util.concurrent.CompletableFuture[]::new);

            java.util.concurrent.CompletableFuture.allOf(futures).join();
            for (var f : futures) {
                list.add((Map<String, Object>) f.get());
            }

            mysqlInstancesCache = list;
            mysqlInstancesCacheTime = now;
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
                double bytes = ((Number) item.getOrDefault("value", 0)).doubleValue();
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
                double cpu = ((Number) item.getOrDefault("value", 0)).doubleValue();
                cpuMap.put(node, cpu);
            }

            Map<String, double[]> nodeMap = new LinkedHashMap<>();
            for (var item : memResults) {
                String node = String.valueOf(item.getOrDefault("instance", "unknown"));
                double mem = ((Number) item.getOrDefault("value", 0)).doubleValue();
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
                    ((Number) b.getOrDefault("cpuUsage", 0)).doubleValue(),
                    ((Number) a.getOrDefault("cpuUsage", 0)).doubleValue()));
            nodeCache = list;
            nodeCacheTime = now;
            log.info("Node 概览: {} 个节点（已缓存）", list.size());
        } catch (Exception e) {
            log.error("查询 Node 概览失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * RocketMQ 实例监控（消息堆积/生产TPS/消费TPS），5 分钟缓存
     */
    public List<Map<String, Object>> rocketmqInstances() {
        long now = System.currentTimeMillis();
        if (rocketmqInstancesCache != null && now - rocketmqInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return rocketmqInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        var configs = middlewareProperties.getRocketmq().getInstances();
        if (configs.isEmpty() || configs.get(0).getInstanceId() == null || configs.get(0).getInstanceId().isBlank()) {
            // 开发环境无配置时返回模拟数据
            return buildMockRocketmqData();
        }

        try {
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var cfg : configs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", cfg.getInstanceId());
                item.put("instanceName", cfg.getInstanceName());
                item.put("topic", cfg.getTopic());

                String dim = "[{\"instanceId\":\"" + cfg.getInstanceId() + "\"}]";
                item.put("messageAccumulation", queryLatestMetric("acs_mq", "MessageAccumulation", dim, startTime, endTime));
                item.put("sendTps", queryLatestMetric("acs_mq", "SendTps", dim, startTime, endTime));
                item.put("consumeTps", queryLatestMetric("acs_mq", "ConsumeTps", dim, startTime, endTime));
                list.add(item);
            }

            rocketmqInstancesCache = list;
            rocketmqInstancesCacheTime = now;
            log.info("RocketMQ 实例监控已缓存: {} 个", list.size());
        } catch (Exception e) {
            log.error("查询 RocketMQ 监控失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Kafka 实例监控（消息堆积/生产TPS/消费TPS），5 分钟缓存
     */
    public List<Map<String, Object>> kafkaInstances() {
        long now = System.currentTimeMillis();
        if (kafkaInstancesCache != null && now - kafkaInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return kafkaInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        var configs = middlewareProperties.getKafka().getInstances();
        if (configs.isEmpty() || configs.get(0).getInstanceId() == null || configs.get(0).getInstanceId().isBlank()) {
            return buildMockKafkaData();
        }

        try {
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var cfg : configs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", cfg.getInstanceId());
                item.put("instanceName", cfg.getInstanceName());
                item.put("topic", cfg.getTopic());

                String dim = "[{\"instanceId\":\"" + cfg.getInstanceId() + "\"}]";
                item.put("lag", queryLatestMetric("acs_kafka", "Lag", dim, startTime, endTime));
                item.put("produceTps", queryLatestMetric("acs_kafka", "ProduceTps", dim, startTime, endTime));
                item.put("consumeTps", queryLatestMetric("acs_kafka", "ConsumeTps", dim, startTime, endTime));
                list.add(item);
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
     * Lindorm 实例监控（CPU/磁盘/QPS），5 分钟缓存
     */
    public List<Map<String, Object>> lindormInstances() {
        long now = System.currentTimeMillis();
        if (lindormInstancesCache != null && now - lindormInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return lindormInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        var configs = middlewareProperties.getLindorm().getInstances();
        if (configs.isEmpty() || configs.get(0).getInstanceId() == null || configs.get(0).getInstanceId().isBlank()) {
            return buildMockLindormData();
        }

        try {
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var cfg : configs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", cfg.getInstanceId());
                item.put("instanceName", cfg.getInstanceName());

                String dim = "[{\"instanceId\":\"" + cfg.getInstanceId() + "\"}]";
                item.put("cpuUsage", queryLatestMetric("acs_lindorm", "CpuUsage", dim, startTime, endTime));
                item.put("diskUsage", queryLatestMetric("acs_lindorm", "DiskUsage", dim, startTime, endTime));
                item.put("qps", queryLatestMetric("acs_lindorm", "Qps", dim, startTime, endTime));
                list.add(item);
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
     * Elasticsearch 实例监控（CPU/磁盘/JVM内存），5 分钟缓存
     */
    public List<Map<String, Object>> elasticsearchInstances() {
        long now = System.currentTimeMillis();
        if (elasticsearchInstancesCache != null && now - elasticsearchInstancesCacheTime < MONITOR_CACHE_TTL_MS) {
            return elasticsearchInstancesCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        var configs = middlewareProperties.getElasticsearch().getInstances();
        if (configs.isEmpty() || configs.get(0).getInstanceId() == null || configs.get(0).getInstanceId().isBlank()) {
            return buildMockElasticsearchData();
        }

        try {
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var cfg : configs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instanceId", cfg.getInstanceId());
                item.put("instanceName", cfg.getInstanceName());

                String dim = "[{\"instanceId\":\"" + cfg.getInstanceId() + "\"}]";
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
     * OSS Bucket 监控（请求数/带宽/错误率），5 分钟缓存
     */
    public List<Map<String, Object>> ossBuckets() {
        long now = System.currentTimeMillis();
        if (ossBucketsCache != null && now - ossBucketsCacheTime < MONITOR_CACHE_TTL_MS) {
            return ossBucketsCache;
        }

        List<Map<String, Object>> list = new ArrayList<>();
        var configs = middlewareProperties.getOss().getBuckets();
        if (configs.isEmpty() || configs.get(0).getBucketName() == null || configs.get(0).getBucketName().isBlank()) {
            return buildMockOssData();
        }

        try {
            String endTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FMT);
            String startTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30).format(FMT);

            for (var cfg : configs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bucketName", cfg.getBucketName());
                item.put("instanceName", cfg.getInstanceName());

                String dim = "[{\"BucketName\":\"" + cfg.getBucketName() + "\"}]";
                item.put("totalRequests", queryLatestMetric("acs_oss", "TotalRequestCount", dim, startTime, endTime));
                item.put("internetSend", queryLatestMetric("acs_oss", "InternetSendBytes", dim, startTime, endTime));
                item.put("errorRate4xx", queryLatestMetric("acs_oss", "ClientErrorRate", dim, startTime, endTime));
                item.put("errorRate5xx", queryLatestMetric("acs_oss", "ServerErrorRate", dim, startTime, endTime));
                list.add(item);
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
     * 查询最新指标值（取最后一个数据点的 Average）
     */
    private double queryLatestMetric(String namespace, String metric, String dimensions,
                                     String startTime, String endTime) {
        List<double[]> points = cloudMonitorClient.queryMetric(namespace, metric, dimensions, 60, startTime, endTime);
        if (points.isEmpty()) return 0;
        return points.get(points.size() - 1)[1];
    }

    // ===== 开发环境模拟数据 =====

    private List<Map<String, Object>> buildMockRocketmqData() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] instances = {
            {"MQ_INST_1647796581073291_G3w1d2", "prod-rocketmq-01", "华东1-可用区A"},
            {"MQ_INST_1647796581073291_B4x8k3", "prod-rocketmq-02", "华东1-可用区B"},
            {"MQ_INST_1647796581073291_C5y9m4", "prod-rocketmq-03", "华东2-可用区A"},
            {"MQ_INST_1647796581073291_D6z0n5", "prod-rocketmq-04", "华东2-可用区B"},
            {"MQ_INST_1647796581073291_E7a1p6", "prod-rocketmq-05", "华北1-可用区A"},
            {"MQ_INST_1647796581073291_F8b2q7", "prod-rocketmq-06", "华北1-可用区B"}
        };
        for (String[] inst : instances) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", inst[0]);
            item.put("instanceName", inst[1]);
            item.put("region", inst[2]);
            item.put("status", "Running");
            item.put("version", "5.x");
            item.put("messageAccumulation", (double) (r.nextInt(500) + 10));
            item.put("sendTps", (double) (r.nextInt(1000) + 100));
            item.put("consumeTps", (double) (r.nextInt(900) + 80));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> buildMockKafkaData() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] instances = {
            {"alikafka_post-cn-7pp2k3x8n01", "prod-kafka-01", "华东1-可用区A"},
            {"alikafka_post-cn-8qq3l4y9n02", "prod-kafka-02", "华东1-可用区B"},
            {"alikafka_post-cn-9rr4m5z0n03", "prod-kafka-03", "华东2-可用区A"},
            {"alikafka_post-cn-0ss5n6a1n04", "prod-kafka-04", "华东2-可用区B"},
            {"alikafka_post-cn-1tt6o7b2n05", "prod-kafka-05", "华北1-可用区A"},
            {"alikafka_post-cn-2uu7p8c3n06", "prod-kafka-06", "华北1-可用区B"},
            {"alikafka_post-cn-3vv8q9d4n07", "prod-kafka-07", "华北2-可用区A"}
        };
        for (String[] inst : instances) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", inst[0]);
            item.put("instanceName", inst[1]);
            item.put("region", inst[2]);
            item.put("status", "Running");
            item.put("version", "3.x");
            item.put("lag", (double) (r.nextInt(1000) + 50));
            item.put("produceTps", (double) (r.nextInt(2000) + 200));
            item.put("consumeTps", (double) (r.nextInt(1800) + 180));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> buildMockLindormData() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] instances = {
            {"ld-bp1a2b3c4d5e6f", "用户画像宽表"},
            {"ld-bp2b3c4d5e6f7g", "订单历史库"},
            {"ld-bp3c4d5e6f7g8h", "设备时序数据"},
            {"ld-bp4d5e6f7g8h9i", "消息存储引擎"},
            {"ld-bp5e6f7g8h9i0j", "地理位置数据"},
            {"ld-bp6f7g8h9i0j1k", "行为分析数据"}
        };
        for (String[] inst : instances) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", inst[0]);
            item.put("instanceName", inst[1]);
            item.put("cpuUsage", 20.0 + r.nextDouble() * 40);
            item.put("diskUsage", 30.0 + r.nextDouble() * 30);
            item.put("qps", (double) (r.nextInt(5000) + 500));
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> buildMockElasticsearchData() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] instances = {
            {"es-cn-zpr3k8x9n001", "日志分析集群"},
            {"es-cn-zqq4l9y0n002", "全文检索集群"},
            {"es-cn-zrr5m0z1n003", "监控指标存储"},
            {"es-cn-zss6n1a2n004", "链路追踪索引"},
            {"es-cn-ztt7o2b3n005", "业务数据检索"}
        };
        for (String[] inst : instances) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", inst[0]);
            item.put("instanceName", inst[1]);
            item.put("cpuUsage", 15.0 + r.nextDouble() * 35);
            item.put("diskUsage", 25.0 + r.nextDouble() * 40);
            item.put("jvmMemory", 30.0 + r.nextDouble() * 30);
            list.add(item);
        }
        return list;
    }

    private List<Map<String, Object>> buildMockOssData() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] buckets = {
            {"hubble-user-avatar-prod", "用户头像存储"},
            {"hubble-order-attachment-prod", "订单附件存储"},
            {"hubble-log-archive-prod", "日志归档存储"},
            {"hubble-backup-daily-prod", "每日备份存储"},
            {"hubble-static-resource-prod", "静态资源存储"},
            {"hubble-export-file-prod", "导出文件存储"},
            {"hubble-media-resource-prod", "媒体资源存储"},
            {"hubble-temp-upload-prod", "临时上传存储"}
        };
        for (String[] bucket : buckets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucketName", bucket[0]);
            item.put("instanceName", bucket[1]);
            item.put("totalRequests", (double) (r.nextInt(100000) + 10000));
            item.put("internetSend", (double) (r.nextInt(1000000000) + 100000000));
            item.put("errorRate4xx", r.nextDouble() * 0.5);
            item.put("errorRate5xx", r.nextDouble() * 0.1);
            list.add(item);
        }
        return list;
    }

    // ===== Top 指标 =====

    public List<Map<String, Object>> rocketmqTopTopics() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] topics = {
            {"topic-order-events", "prod-rocketmq-01", "MQ_INST_1647796581073291_G3w1d2"},
            {"topic-payment-notify", "prod-rocketmq-02", "MQ_INST_1647796581073291_B4x8k3"},
            {"topic-logistics-sync", "prod-rocketmq-03", "MQ_INST_1647796581073291_C5y9m4"},
            {"topic-inventory-change", "prod-rocketmq-04", "MQ_INST_1647796581073291_D6z0n5"},
            {"topic-user-behavior", "prod-rocketmq-05", "MQ_INST_1647796581073291_E7a1p6"},
            {"topic-risk-alert", "prod-rocketmq-06", "MQ_INST_1647796581073291_F8b2q7"},
            {"topic-notification", "prod-rocketmq-01", "MQ_INST_1647796581073291_G3w1d2"},
            {"topic-data-sync", "prod-rocketmq-02", "MQ_INST_1647796581073291_B4x8k3"},
            {"topic-audit-log", "prod-rocketmq-03", "MQ_INST_1647796581073291_C5y9m4"},
            {"topic-analytics", "prod-rocketmq-04", "MQ_INST_1647796581073291_D6z0n5"}
        };
        for (int i = 0; i < topics.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("topic", topics[i][0]);
            item.put("instanceName", topics[i][1]);
            item.put("instanceId", topics[i][2]);
            item.put("messageAccumulation", (double) (r.nextInt(5000) + 100));
            item.put("sendTps", (double) (r.nextInt(2000) + 200));
            item.put("consumeTps", (double) (r.nextInt(1800) + 180));
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
            ((Number) b.get("messageAccumulation")).doubleValue(),
            ((Number) a.get("messageAccumulation")).doubleValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> kafkaTopPartitions() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] topics = {
            {"topic-app-log", "prod-kafka-01", "alikafka_post-cn-7pp2k3x8n01"},
            {"topic-event-bus", "prod-kafka-02", "alikafka_post-cn-8qq3l4y9n02"},
            {"topic-data-sync", "prod-kafka-03", "alikafka_post-cn-9rr4m5z0n03"},
            {"topic-metrics", "prod-kafka-04", "alikafka_post-cn-0ss5n6a1n04"},
            {"topic-audit-log", "prod-kafka-05", "alikafka_post-cn-1tt6o7b2n05"},
            {"topic-flink-compute", "prod-kafka-06", "alikafka_post-cn-2uu7p8c3n06"},
            {"topic-iot-device", "prod-kafka-07", "alikafka_post-cn-3vv8q9d4n07"},
            {"topic-user-activity", "prod-kafka-01", "alikafka_post-cn-7pp2k3x8n01"},
            {"topic-order-event", "prod-kafka-02", "alikafka_post-cn-8qq3l4y9n02"},
            {"topic-payment-status", "prod-kafka-03", "alikafka_post-cn-9rr4m5z0n03"}
        };
        for (int i = 0; i < topics.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("topic", topics[i][0]);
            item.put("instanceName", topics[i][1]);
            item.put("instanceId", topics[i][2]);
            item.put("lag", (double) (r.nextInt(10000) + 500));
            item.put("produceTps", (double) (r.nextInt(3000) + 300));
            item.put("consumeTps", (double) (r.nextInt(2800) + 280));
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
            ((Number) b.get("lag")).doubleValue(),
            ((Number) a.get("lag")).doubleValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> redisBigKeys() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] keys = {
            {"user:session:10086", "string", "用户会话缓存"},
            {"order:detail:20240905001", "hash", "订单详情缓存"},
            {"product:stock:50012", "string", "商品库存缓存"},
            {"user:profile:10087", "hash", "用户画像缓存"},
            {"cart:items:10088", "list", "购物车列表"},
            {"rate:limit:api:gateway", "string", "API限流计数器"},
            {"leaderboard:daily:20240905", "zset", "每日排行榜"},
            {"config:feature:flags", "hash", "功能开关配置"},
            {"cache:hot:products", "set", "热门商品集合"},
            {"lock:order:create:20240905002", "string", "分布式锁"}
        };
        for (int i = 0; i < keys.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("key", keys[i][0]);
            item.put("type", keys[i][1]);
            item.put("description", keys[i][2]);
            item.put("memoryBytes", (long) (r.nextInt(10000000) + 100000));
            item.put("ttl", r.nextInt(86400));
            item.put("idleSeconds", r.nextInt(3600));
            list.add(item);
        }
        list.sort((a, b) -> Long.compare(
            ((Number) b.get("memoryBytes")).longValue(),
            ((Number) a.get("memoryBytes")).longValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> redisSlowQueries() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] commands = {
            {"KEYS", "user:*", "匹配大量键"},
            {"SMEMBERS", "cache:hot:products", "大集合查询"},
            {"HGETALL", "user:profile:10087", "大Hash查询"},
            {"LRANGE", "cart:items:10088", "0", "-1", "大List全量查询"},
            {"ZRANGE", "leaderboard:daily:20240905", "0", "-1", "大ZSet全量查询"},
            {"SORT", "product:ids", "大集合排序"},
            {"SCAN", "0", "MATCH", "order:*", "游标遍历"},
            {"SINTER", "set1", "set2", "set3", "多集合交集"},
            {"HSCAN", "order:detail:20240905001", "0", "大Hash遍历"},
            {"MGET", "key1", "key2", "...", "批量查询(50+键)"}
        };
        for (int i = 0; i < commands.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("command", String.join(" ", commands[i]));
            item.put("durationMicros", (long) (r.nextInt(500000) + 10000));
            item.put("timestamp", System.currentTimeMillis() - r.nextInt(3600000));
            item.put("clientAddr", "10.0." + r.nextInt(255) + "." + r.nextInt(255));
            list.add(item);
        }
        list.sort((a, b) -> Long.compare(
            ((Number) b.get("durationMicros")).longValue(),
            ((Number) a.get("durationMicros")).longValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> mysqlTopTables() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] tables = {
            {"order_main", "订单主表", "InnoDB"},
            {"order_detail", "订单明细表", "InnoDB"},
            {"user_profile", "用户画像表", "InnoDB"},
            {"payment_record", "支付记录表", "InnoDB"},
            {"product_sku", "商品SKU表", "InnoDB"},
            {"inventory_log", "库存流水表", "InnoDB"},
            {"notification_log", "通知日志表", "InnoDB"},
            {"api_access_log", "API访问日志", "InnoDB"},
            {"device_data", "设备数据表", "InnoDB"},
            {"analytics_event", "分析事件表", "InnoDB"}
        };
        for (int i = 0; i < tables.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("tableName", tables[i][0]);
            item.put("description", tables[i][1]);
            item.put("engine", tables[i][2]);
            item.put("rowCount", (long) (r.nextInt(10000000) + 100000));
            item.put("dataSizeMB", (double) (r.nextInt(5000) + 100));
            item.put("indexSizeMB", (double) (r.nextInt(1000) + 50));
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
            ((Number) b.get("dataSizeMB")).doubleValue(),
            ((Number) a.get("dataSizeMB")).doubleValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> mysqlSlowQueries() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] queries = {
            {"SELECT * FROM order_main WHERE user_id = ? AND created_at > ? ORDER BY created_at DESC LIMIT 100", "订单列表查询"},
            {"SELECT COUNT(*) FROM order_detail WHERE order_id IN (SELECT id FROM order_main WHERE status = ?)", "订单统计子查询"},
            {"UPDATE inventory_log SET status = ? WHERE product_id = ? AND warehouse_id = ? AND created_at > ?", "库存更新"},
            {"SELECT u.*, p.* FROM user_profile u LEFT JOIN payment_record p ON u.id = p.user_id WHERE u.created_at > ?", "用户支付关联查询"},
            {"SELECT * FROM api_access_log WHERE request_time BETWEEN ? AND ? AND response_time > ? ORDER BY response_time DESC", "慢API日志查询"},
            {"INSERT INTO analytics_event SELECT * FROM temp_events WHERE event_date = ?", "分析数据批量插入"},
            {"SELECT product_id, SUM(quantity) FROM order_detail GROUP BY product_id HAVING SUM(quantity) > ? ORDER BY SUM(quantity) DESC", "商品销量统计"},
            {"DELETE FROM notification_log WHERE created_at < ? AND status = ?", "历史通知清理"},
            {"SELECT * FROM device_data WHERE device_id = ? AND timestamp BETWEEN ? AND ? ORDER BY timestamp", "设备数据时序查询"},
            {"SELECT DISTINCT user_id FROM order_main WHERE created_at > ? AND amount > ? GROUP BY user_id HAVING COUNT(*) > ?", "高频用户分析"}
        };
        for (int i = 0; i < queries.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("sql", queries[i][0]);
            item.put("description", queries[i][1]);
            item.put("durationMs", (long) (r.nextInt(10000) + 500));
            item.put("rowsExamined", (long) (r.nextInt(1000000) + 10000));
            item.put("rowsReturned", (long) (r.nextInt(10000) + 100));
            item.put("timestamp", System.currentTimeMillis() - r.nextInt(3600000));
            list.add(item);
        }
        list.sort((a, b) -> Long.compare(
            ((Number) b.get("durationMs")).longValue(),
            ((Number) a.get("durationMs")).longValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> lindormTopTables() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] tables = {
            {"user_profile_wide", "用户画像宽表"},
            {"order_history", "订单历史库"},
            {"device_timeseries", "设备时序数据"},
            {"message_store", "消息存储引擎"},
            {"geo_location", "地理位置数据"},
            {"behavior_analytics", "行为分析数据"},
            {"product_catalog", "商品目录表"},
            {"transaction_log", "交易日志表"},
            {"notification_history", "通知历史表"},
            {"audit_trail", "审计追踪表"}
        };
        for (int i = 0; i < tables.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("tableName", tables[i][0]);
            item.put("description", tables[i][1]);
            item.put("readQps", (double) (r.nextInt(5000) + 200));
            item.put("writeQps", (double) (r.nextInt(3000) + 100));
            item.put("rowCount", (long) (r.nextInt(50000000) + 1000000));
            item.put("storageMB", (double) (r.nextInt(10000) + 500));
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
            ((Number) b.get("readQps")).doubleValue() + ((Number) b.get("writeQps")).doubleValue(),
            ((Number) a.get("readQps")).doubleValue() + ((Number) a.get("writeQps")).doubleValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }

    public List<Map<String, Object>> elasticsearchTopIndices() {
        List<Map<String, Object>> list = new ArrayList<>();
        Random r = new Random();
        String[][] indices = {
            {"app-log-2024.09", "应用日志索引"},
            {"audit-log-2024.09", "审计日志索引"},
            {"trace-data-2024.09", "链路追踪索引"},
            {"metrics-2024.09", "监控指标索引"},
            {"business-event-2024.09", "业务事件索引"},
            {"order-search-2024", "订单检索索引"},
            {"product-search", "商品检索索引"},
            {"user-behavior-2024.09", "用户行为索引"},
            {"notification-log-2024.09", "通知日志索引"},
            {"device-data-2024.09", "设备数据索引"}
        };
        for (int i = 0; i < indices.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("indexName", indices[i][0]);
            item.put("description", indices[i][1]);
            item.put("docCount", (long) (r.nextInt(100000000) + 1000000));
            item.put("storageGB", (double) (r.nextInt(500) + 10));
            item.put("shardCount", r.nextInt(10) + 1);
            item.put("replicaCount", 1);
            list.add(item);
        }
        list.sort((a, b) -> Double.compare(
            ((Number) b.get("storageGB")).doubleValue(),
            ((Number) a.get("storageGB")).doubleValue()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i + 1);
        }
        return list;
    }
}
