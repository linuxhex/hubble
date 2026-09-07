package com.ykc.hubble.dto;

import lombok.Data;

import java.util.Map;

@Data
public class GatewayLogQueryDTO {
    private String appName;
    private String url;
    private String statusCode;
    private String phone;
    private String userId;
    private String traceId;
    private Integer page = 1;
    private Integer pageSize = 20;

    // KeywordLogQuery 页面使用的字段
    private String keyword;
    private String logstore;
    private Map<String, Long> timeRange;
    private Integer offset;
    private Integer limit;
    private String sortOrder;
}
