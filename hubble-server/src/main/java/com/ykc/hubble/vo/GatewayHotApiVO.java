package com.ykc.hubble.vo;

import lombok.Data;

@Data
public class GatewayHotApiVO {
    private String path;
    private String method;
    private double qps;
    private String avgTime;
    private String errorRate;
}
