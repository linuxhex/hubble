package com.ykc.hubble.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 概览数据时序点
 *
 * @author Cloud Eyes Team
 */
@Data
@AllArgsConstructor
public class OverviewSnapshotPoint {

    /**
     * 采集时间（Unix 时间戳，秒）
     */
    private long collectedAt;

    /**
     * 总请求数
     */
    private long totalRequests;

    /**
     * 错误数
     */
    private long errorCount;

    /**
     * 平均响应时间（毫秒）
     */
    private double avgResponseTime;

    /**
     * QPS
     */
    private double qps;

    /**
     * 错误率（百分比）
     */
    private double errorRate;
}
