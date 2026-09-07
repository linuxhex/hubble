package com.ykc.hubble.client;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.Bucket;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.alikafka.model.v20190916.GetInstanceListRequest;
import com.aliyuncs.alikafka.model.v20190916.GetInstanceListResponse;
import com.aliyuncs.alikafka.model.v20190916.GetTopicListRequest;
import com.aliyuncs.alikafka.model.v20190916.GetTopicListResponse;
import com.aliyuncs.cms.model.v20190101.DescribeMetricListRequest;
import com.aliyuncs.cms.model.v20190101.DescribeMetricListResponse;
import com.aliyuncs.cms.model.v20190101.DescribeMetricMetaListRequest;
import com.aliyuncs.cms.model.v20190101.DescribeMetricMetaListResponse;
import com.aliyuncs.elasticsearch.model.v20170613.ListInstanceRequest;
import com.aliyuncs.elasticsearch.model.v20170613.ListInstanceResponse;
import com.aliyuncs.hitsdb.model.v20200615.GetLindormInstanceListRequest;
import com.aliyuncs.hitsdb.model.v20200615.GetLindormInstanceListResponse;
import com.aliyuncs.ons.model.v20190214.OnsInstanceInServiceListRequest;
import com.aliyuncs.ons.model.v20190214.OnsInstanceInServiceListResponse;
import com.aliyuncs.ons.model.v20190214.OnsTopicListRequest;
import com.aliyuncs.ons.model.v20190214.OnsTopicListResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.rds.model.v20140815.DescribeDBInstancesRequest;
import com.aliyuncs.rds.model.v20140815.DescribeDBInstancesResponse;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeInstancesRequest;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeInstancesResponse;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeSlowLogRecordsRequest;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeSlowLogRecordsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 CloudMonitor + RDS + Redis(KVStore) 客户端
 * 用于查询 MySQL/PolarDB/Redis 的 CPU/连接数/内存/QPS/慢查询等监控指标。
 */
@Slf4j
@Component
public class CloudMonitorClient {

    @Value("${aliyun.sls.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.sls.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.arms.region:cn-hangzhou}")
    private String region;

    private IAcsClient acsClient;

    @PostConstruct
    public void init() {
        try {
            DefaultProfile profile = DefaultProfile.getProfile(region, accessKeyId, accessKeySecret);
            DefaultProfile.addEndpoint(region, "Cms", "metrics.aliyuncs.com");
            this.acsClient = new DefaultAcsClient(profile);
            log.info("CloudMonitorClient 初始化完成, region={}, endpoint=metrics.aliyuncs.com", region);
        } catch (Exception e) {
            log.error("CloudMonitorClient 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 查询 CloudMonitor 指标数据（通用方法）
     *
     * @param namespace  命名空间，如 "acs_kvstore"（Redis）、"acs_rds_new"（RDS）
     * @param metricName 指标名，如 "CpuUsage"、"Connections"、"MemoryUsage"
     * @param dimensions 维度 JSON，如 [{"instanceId":"r-xxx"}]
     * @param period     聚合周期（秒），如 60、300、3600
     * @param startTime  开始时间（ISO格式 yyyy-MM-dd HH:mm:ss）
     * @param endTime    结束时间
     * @return 指标数据点列表（每个点含 timestamp + value）
     */
    public List<double[]> queryMetric(String namespace, String metricName, String dimensions,
                                       int period, String startTime, String endTime) {
        try {
            DescribeMetricListRequest req = new DescribeMetricListRequest();
            req.setNamespace(namespace);
            req.setMetricName(metricName);
            req.setDimensions(dimensions);
            req.setPeriod(String.valueOf(period));
            req.setStartTime(startTime);
            req.setEndTime(endTime);

            DescribeMetricListResponse resp = acsClient.getAcsResponse(req);
            List<double[]> result = new ArrayList<>();

            String datapoints = resp.getDatapoints();
            if (datapoints != null && !datapoints.isEmpty()) {
                log.debug("CloudMonitor 原始数据: ns={}, metric={}, dim={}, len={}", namespace, metricName, dimensions, datapoints.length());
                com.alibaba.fastjson.JSONArray arr = com.alibaba.fastjson.JSON.parseArray(datapoints);
                for (int i = 0; i < arr.size(); i++) {
                    com.alibaba.fastjson.JSONObject obj = arr.getJSONObject(i);
                    double ts = obj.getLongValue("timestamp");
                    double avg = obj.containsKey("Average") ? obj.getDoubleValue("Average") : 0;
                    result.add(new double[]{ts, avg});
                }
            } else {
                log.info("CloudMonitor 无数据: ns={}, metric={}, dim={}", namespace, metricName, dimensions);
            }
            return result;
        } catch (Exception e) {
            log.warn("CloudMonitor 查询失败: ns={}, metric={}, dim={}, error={}", namespace, metricName, dimensions, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 RDS/PolarDB 实例列表
     */
    public List<DescribeDBInstancesResponse.DBInstance> listRdsInstances() {
        try {
            DescribeDBInstancesRequest req = new DescribeDBInstancesRequest();
            req.setPageSize(100);
            DescribeDBInstancesResponse resp = acsClient.getAcsResponse(req);
            return resp.getItems();
        } catch (Exception e) {
            log.warn("查询 RDS 实例列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 Redis(KVStore) 实例列表
     */
    public List<DescribeInstancesResponse.KVStoreInstance> listRedisInstances() {
        try {
            DescribeInstancesRequest req = new DescribeInstancesRequest();
            req.setPageSize(100);
            DescribeInstancesResponse resp = acsClient.getAcsResponse(req);
            return resp.getInstances();
        } catch (Exception e) {
            log.warn("查询 Redis 实例列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 RocketMQ 实例列表
     */
    public List<OnsInstanceInServiceListResponse.InstanceVO> listRocketMQInstances() {
        try {
            OnsInstanceInServiceListRequest req = new OnsInstanceInServiceListRequest();
            OnsInstanceInServiceListResponse resp = acsClient.getAcsResponse(req);
            return resp.getData();
        } catch (Exception e) {
            log.warn("查询 RocketMQ 实例列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 Kafka 实例列表
     */
    public List<Map<String, Object>> listKafkaInstances() {
        try {
            GetInstanceListRequest req = new GetInstanceListRequest();
            GetInstanceListResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getInstanceList() != null) {
                for (var inst : resp.getInstanceList()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("instanceId", inst.getInstanceId());
                    map.put("name", inst.getName());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 Kafka 实例列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 Elasticsearch 实例列表
     */
    public List<Map<String, Object>> listElasticsearchInstances() {
        try {
            ListInstanceRequest req = new ListInstanceRequest();
            req.setSize(100);
            ListInstanceResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getResult() != null) {
                for (var inst : resp.getResult()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("instanceId", inst.getInstanceId());
                    map.put("description", inst.getDescription());
                    map.put("status", inst.getStatus());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 Elasticsearch 实例列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 Lindorm 实例列表
     */
    public List<Map<String, Object>> listLindormInstances() {
        try {
            GetLindormInstanceListRequest req = new GetLindormInstanceListRequest();
            req.setPageSize(100);
            GetLindormInstanceListResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getInstanceList() != null) {
                for (var inst : resp.getInstanceList()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("instanceId", inst.getInstanceId());
                    map.put("instanceAlias", inst.getInstanceAlias());
                    map.put("status", inst.getInstanceStatus());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 Lindorm 实例列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 OSS Bucket 列表
     */
    public List<Bucket> listOSSBuckets() {
        OSS ossClient = null;
        try {
            String endpoint = "https://oss-" + region + ".aliyuncs.com";
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            return ossClient.listBuckets();
        } catch (Exception e) {
            log.warn("查询 OSS Bucket 列表失败: {}", e.getMessage());
            return new ArrayList<>();
        } finally {
            if (ossClient != null) {
                try {
                    ossClient.shutdown();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 查询 CloudMonitor 可用的指标元数据（诊断用）
     */
    public List<String> listMetricMeta(String namespace) {
        try {
            DescribeMetricMetaListRequest req = new DescribeMetricMetaListRequest();
            req.setNamespace(namespace);
            DescribeMetricMetaListResponse resp = acsClient.getAcsResponse(req);
            List<String> metrics = new ArrayList<>();
            if (resp.getResources() != null) {
                for (var resource : resp.getResources()) {
                    metrics.add(resource.getMetricName() + " - " + resource.getDescription());
                }
            }
            return metrics;
        } catch (Exception e) {
            log.warn("查询指标元数据失败: ns={}, error={}", namespace, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 RocketMQ 实例的 Topic 列表
     */
    public List<Map<String, Object>> listRocketMQTopics(String instanceId) {
        try {
            OnsTopicListRequest req = new OnsTopicListRequest();
            req.setInstanceId(instanceId);
            OnsTopicListResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getData() != null) {
                for (var topic : resp.getData()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("topic", topic.getTopic());
                    map.put("instanceId", instanceId);
                    map.put("remark", topic.getRemark());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 RocketMQ Topic 列表失败: instanceId={}, error={}", instanceId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 Kafka 实例的 Topic 列表
     */
    public List<Map<String, Object>> listKafkaTopics(String instanceId) {
        try {
            GetTopicListRequest req = new GetTopicListRequest();
            req.setInstanceId(instanceId);
            req.setCurrentPage("1");
            req.setPageSize("100");
            GetTopicListResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getTopicList() != null) {
                for (var topic : resp.getTopicList()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("topic", topic.getTopic());
                    map.put("instanceId", instanceId);
                    map.put("partitionNum", topic.getPartitionNum());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 Kafka Topic 列表失败: instanceId={}, error={}", instanceId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 Redis 实例的慢查询日志
     */
    public List<Map<String, Object>> listRedisSlowLogs(String instanceId, String startTime, String endTime) {
        try {
            DescribeSlowLogRecordsRequest req = new DescribeSlowLogRecordsRequest();
            req.setInstanceId(instanceId);
            req.setStartTime(startTime);
            req.setEndTime(endTime);
            req.setPageSize(50);
            DescribeSlowLogRecordsResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getItems() != null) {
                for (var record : resp.getItems()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("command", record.getCommand());
                    map.put("elapsedTime", record.getElapsedTime());
                    map.put("executeTime", record.getExecuteTime());
                    map.put("ipAddress", record.getIPAddress());
                    map.put("dbName", record.getDataBaseName());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 Redis 慢查询失败: instanceId={}, error={}", instanceId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询 RDS 实例的慢查询日志
     */
    public List<Map<String, Object>> listRdsSlowLogs(String instanceId, String startTime, String endTime) {
        try {
            com.aliyuncs.rds.model.v20140815.DescribeSlowLogRecordsRequest req =
                    new com.aliyuncs.rds.model.v20140815.DescribeSlowLogRecordsRequest();
            req.setDBInstanceId(instanceId);
            req.setStartTime(startTime);
            req.setEndTime(endTime);
            req.setPageSize(50);
            com.aliyuncs.rds.model.v20140815.DescribeSlowLogRecordsResponse resp = acsClient.getAcsResponse(req);
            List<Map<String, Object>> result = new ArrayList<>();
            if (resp.getItems() != null) {
                for (var record : resp.getItems()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("sqlText", record.getSQLText());
                    map.put("queryTimes", record.getQueryTimes());
                    map.put("hostAddress", record.getHostAddress());
                    map.put("dbName", record.getDBName());
                    map.put("returnRowCounts", record.getReturnRowCounts());
                    map.put("parseRowCounts", record.getParseRowCounts());
                    result.add(map);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("查询 RDS 慢查询失败: instanceId={}, error={}", instanceId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
