package com.ykc.hubble.client;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.arms.model.v20190808.GetTraceRequest;
import com.aliyuncs.arms.model.v20190808.GetTraceResponse;
import com.aliyuncs.arms.model.v20190808.ListTraceAppsRequest;
import com.aliyuncs.arms.model.v20190808.ListTraceAppsResponse;
import com.aliyuncs.arms.model.v20190808.QueryMetricByPageRequest;
import com.aliyuncs.arms.model.v20190808.QueryMetricByPageResponse;
import com.aliyuncs.arms.model.v20190808.SearchTracesRequest;
import com.aliyuncs.arms.model.v20190808.SearchTracesResponse;
import com.ykc.hubble.config.ArmsConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 阿里云 ARMS 客户端：应用监控与链路追踪。
 * <p>
 * 三个核心能力（对照 cloud-eyes 的 arms_curl.sh 蓝本）：
 * <ul>
 *   <li>{@link #listApps()} - ListTraceApps：取应用列表 / pid</li>
 *   <li>{@link #searchTraces(String, long, long)} - SearchTraces：按 pid+时间窗捞链路列表</li>
 *   <li>{@link #getTrace(String, Long, Long)} - GetTrace：按 traceId 取 span 树（上下游）</li>
 * </ul>
 * 注意：ARMS 时间参数为毫秒（SLS 是秒）。
 *
 * @author Hubble Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArmsClient {

    private final ArmsConfig armsConfig;

    private IAcsClient client;

    @PostConstruct
    public void init() {
        // 创建 RPC 风格 client；AK 留空时不影响启动，仅在实际调用时报错
        DefaultProfile profile = DefaultProfile.getProfile(
                armsConfig.getRegion(),
                armsConfig.getAccessKeyId(),
                armsConfig.getAccessKeySecret());
        // 显式超时：默认无界，慢响应会占死调用线程（@Scheduled 单线程池会被饿死）
        com.aliyuncs.http.HttpClientConfig httpClientConfig = com.aliyuncs.http.HttpClientConfig.getDefault();
        httpClientConfig.setConnectionTimeoutMillis(5 * 1000L);
        httpClientConfig.setReadTimeoutMillis(15 * 1000L);
        profile.setHttpClientConfig(httpClientConfig);
        this.client = new DefaultAcsClient(profile);
        log.info("ARMS client 初始化: region={}", armsConfig.getRegion());
    }

    /**
     * 获取 ARMS 应用列表（含 pid，后续查询必传）
     */
    public ListTraceAppsResponse listApps() throws Exception {
        ListTraceAppsRequest req = new ListTraceAppsRequest();
        req.setRegionId(armsConfig.getRegion());
        return client.getAcsResponse(req);
    }

    /**
     * 按时间窗搜索链路列表（2.7.21 版 SearchTracesRequest 无 setPid，按 region+时间窗搜索；
     * 可用 ServiceName/OperationName 进一步过滤）
     */
    public SearchTracesResponse searchTraces(String serviceName, long fromMs, long toMs) throws Exception {
        SearchTracesRequest req = new SearchTracesRequest();
        req.setRegionId(armsConfig.getRegion());
        req.setStartTime(fromMs);
        req.setEndTime(toMs);
        req.setReverse(true);
        if (serviceName != null && !serviceName.isEmpty()) {
            req.setServiceName(serviceName);
        }
        return client.getAcsResponse(req);
    }

    /**
     * 按 traceId 获取单条链路的 span 树（2.7.21 版 GetTraceRequest 仅支持 TraceID）
     */
    public GetTraceResponse getTrace(String traceId, Long fromMs, Long toMs) throws Exception {
        GetTraceRequest req = new GetTraceRequest();
        req.setRegionId(armsConfig.getRegion());
        req.setTraceID(traceId);
        // 通过 QueryParam 设置时间参数（SDK 没有对应的 setter 方法）
        if (fromMs != null) {
            req.putQueryParameter("StartTime", fromMs);
        }
        if (toMs != null) {
            req.putQueryParameter("EndTime", toMs);
        }
        return client.getAcsResponse(req);
    }

    /**
     * 查询指标数据（如 API 响应时间、调用次数等）
     * 
     * @param metric 指标名称，如 "appstat.transaction" 表示接口调用统计
     * @param measures 要查询的度量，如 ["rt", "count"] 表示响应时间和调用次数
     * @param fromMs 开始时间（毫秒）
     * @param toMs 结束时间（毫秒）
     * @param pid 应用PID（可选）
     * @param intervalInSec 聚合粒度（秒），如 60 表示按分钟，3600 表示按小时，86400 表示按天
     * @return 指标数据
     */
    public QueryMetricByPageResponse queryMetrics(String metric, List<String> measures, 
            long fromMs, long toMs, String pid, int intervalInSec) throws Exception {
        QueryMetricByPageRequest req = new QueryMetricByPageRequest();
        req.setRegionId(armsConfig.getRegion());
        req.setMetric(metric);
        req.setMeasuress(measures);
        req.setStartTime(fromMs);
        req.setEndTime(toMs);
        req.setIntervalInSec(intervalInSec);
        req.setCurrentPage(1);
        req.setPageSize(1000);
        
        if (pid != null && !pid.isEmpty()) {
            QueryMetricByPageRequest.Filters filter = new QueryMetricByPageRequest.Filters();
            filter.setKey("pid");
            filter.setValue(pid);
            req.setFilterss(Arrays.asList(filter));
        }
        
        log.info("ArmsClient.queryMetrics: metric={}, fromMs={}, toMs={}, pid={}, intervalInSec={}",
            metric, fromMs, toMs, pid, intervalInSec);

        QueryMetricByPageResponse response = client.getAcsResponse(req);

        if (response != null && response.getData() != null && response.getData().getItems() != null) {
            int size = response.getData().getItems().size();
            log.info("ArmsClient.queryMetrics 返回 {} 条数据", size);
            if (size == 0) {
                log.warn("ArmsClient.queryMetrics 返回空items, response.getData()={}", response.getData());
            }
        } else {
            log.warn("ArmsClient.queryMetrics 返回空数据, response={}, data={}",
                response, response != null ? response.getData() : "null");
        }

        return response;
    }
    
    /**
     * 查询指标数据（重载方法，默认按天聚合）
     */
    public QueryMetricByPageResponse queryMetrics(String metric, List<String> measures,
            long fromMs, long toMs, String pid) throws Exception {
        return queryMetrics(metric, measures, fromMs, toMs, pid, 86400);
    }

    /**
     * 查询指标数据（带维度聚合，如 dimensions=["rpc"] 按接口聚合，AI 排查工具用）
     */
    public QueryMetricByPageResponse queryMetricsWithDimension(String metric, List<String> measures,
            long fromMs, long toMs, String pid, int intervalInSec, List<String> dimensions) throws Exception {
        QueryMetricByPageRequest req = new QueryMetricByPageRequest();
        req.setRegionId(armsConfig.getRegion());
        req.setMetric(metric);
        req.setMeasuress(measures);
        req.setStartTime(fromMs);
        req.setEndTime(toMs);
        req.setIntervalInSec(intervalInSec);
        req.setCurrentPage(1);
        req.setPageSize(1000);
        if (dimensions != null && !dimensions.isEmpty()) {
            req.setDimensionss(dimensions);
        }

        List<QueryMetricByPageRequest.Filters> filters = new ArrayList<>();
        if (pid != null && !pid.isEmpty()) {
            QueryMetricByPageRequest.Filters pidFilter = new QueryMetricByPageRequest.Filters();
            pidFilter.setKey("pid");
            pidFilter.setValue(pid);
            filters.add(pidFilter);
        }
        QueryMetricByPageRequest.Filters regionFilter = new QueryMetricByPageRequest.Filters();
        regionFilter.setKey("regionId");
        regionFilter.setValue(armsConfig.getRegion());
        filters.add(regionFilter);
        req.setFilterss(filters);

        return client.getAcsResponse(req);
    }
}
