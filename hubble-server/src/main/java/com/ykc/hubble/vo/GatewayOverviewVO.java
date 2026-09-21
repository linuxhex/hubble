package com.ykc.hubble.vo;

import lombok.Data;

@Data
public class GatewayOverviewVO {
    private long totalRequests;
    private double avgResponseTime;
    private double errorRate;
    private double qps;
    private double totalTrend;
    private double avgTrend;
    private double errorTrend;
    private double qpsTrend;
    /** 数据来源：ARMS / SLS（SLS 表示 ARMS 不可用时的降级口径） */
    private String dataSource;
}
