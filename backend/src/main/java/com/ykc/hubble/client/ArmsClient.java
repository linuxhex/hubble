package com.ykc.hubble.client;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.arms.model.v20190808.GetTraceRequest;
import com.aliyuncs.arms.model.v20190808.GetTraceResponse;
import com.aliyuncs.arms.model.v20190808.ListTraceAppsRequest;
import com.aliyuncs.arms.model.v20190808.ListTraceAppsResponse;
import com.aliyuncs.arms.model.v20190808.SearchTracesRequest;
import com.aliyuncs.arms.model.v20190808.SearchTracesResponse;
import com.ykc.hubble.config.ArmsConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    public SearchTracesResponse searchTraces(String pid, long fromMs, long toMs) throws Exception {
        SearchTracesRequest req = new SearchTracesRequest();
        req.setRegionId(armsConfig.getRegion());
        req.setStartTime(fromMs);
        req.setEndTime(toMs);
        req.setReverse(true);
        return client.getAcsResponse(req);
    }

    /**
     * 按 traceId 获取单条链路的 span 树（2.7.21 版 GetTraceRequest 仅支持 TraceID）
     */
    public GetTraceResponse getTrace(String traceId, Long fromMs, Long toMs) throws Exception {
        GetTraceRequest req = new GetTraceRequest();
        req.setRegionId(armsConfig.getRegion());
        req.setTraceID(traceId);
        return client.getAcsResponse(req);
    }
}
