package com.ykc.hubble.service;

import com.ykc.hubble.config.MonitorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 概览数据快照采集服务：按固定周期采集概览数据，
 * 结果写入快照缓存，供概览接口快速读取时序数据。
 * <p>
 * 采集任务提交到 queryExecutor 线程池并行执行，单个时间范围采集失败不影响其他时间范围。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverviewSnapshotService {

    private final GatewayService gatewayService;
    private final OverviewSnapshotCache overviewSnapshotCache;
    private final MonitorProperties monitorProperties;

    /**
     * 需要采集的时间范围列表
     */
    private static final List<String> TIME_RANGES = Arrays.asList("15m", "30m", "1h", "6h", "1d");

    /**
     * 定时采集：周期由 monitor.scan-interval-seconds 控制（默认 60s）
     */
    @Scheduled(fixedDelayString = "${monitor.scan-interval-seconds:60}000", initialDelayString = "60000")
    public void collect() {
        long now = System.currentTimeMillis() / 1000;
        
        for (String timeRange : TIME_RANGES) {
            try {
                collectOne(timeRange, now);
            } catch (Exception e) {
                log.error("采集概览数据失败 [{}]: {}", timeRange, e.getMessage());
            }
        }
    }

    private void collectOne(String timeRange, long now) {
        try {
            // 调用 GatewayService 获取概览数据
            var overview = gatewayService.overview(timeRange);
            
            if (overview != null) {
                // 计算错误数：总请求数 * 错误率
                long errorCount = Math.round(overview.getTotalRequests() * overview.getErrorRate() / 100.0);
                
                // 存储到快照缓存
                overviewSnapshotCache.push(
                    timeRange,
                    now,
                    overview.getTotalRequests(),
                    errorCount,
                    overview.getAvgResponseTime(),
                    overview.getQps(),
                    overview.getErrorRate()
                );
                
                log.debug("采集概览数据成功 [{}]: totalRequests={}, qps={}", 
                         timeRange, overview.getTotalRequests(), overview.getQps());
            }
        } catch (Exception e) {
            log.warn("采集概览数据失败 [{}]: {}", timeRange, e.getMessage());
        }
    }
}
