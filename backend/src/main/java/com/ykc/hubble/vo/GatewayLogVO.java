package com.ykc.hubble.vo;

import lombok.Data;

@Data
public class GatewayLogVO {
    private String appName;
    private String serverIp;
    private String url;
    private String userId;
    private double duration;
    private String timestamp;
    private int statusCode;
    private String message;
    private String traceId;
    private String downstreamService;
}
