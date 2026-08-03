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
}
