package com.ykc.hubble.client;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.cms.model.v20190101.DescribeMetricListRequest;
import com.aliyuncs.cms.model.v20190101.DescribeMetricListResponse;
import com.aliyuncs.cms.model.v20190101.DescribeMetricMetaListRequest;
import com.aliyuncs.cms.model.v20190101.DescribeMetricMetaListResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.rds.model.v20140815.DescribeDBInstancesRequest;
import com.aliyuncs.rds.model.v20140815.DescribeDBInstancesResponse;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeInstancesRequest;
import com.aliyuncs.r_kvstore.model.v20150101.DescribeInstancesResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

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
}
