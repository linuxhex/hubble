package com.ykc.hubble.vo;

import lombok.Data;

/**
 * 接口环比劣化排名项
 */
@Data
public class ApiDegradationVO {

    /** 排名 */
    private int rank;

    /** 接口路径 */
    private String apiPath;

    /** 当前平均耗时 (ms) */
    private double currentAvgTime;

    /** 上期平均耗时 (ms) */
    private double previousAvgTime;

    /** 劣化幅度 (%) */
    private double degradationRate;

    /** 当前请求数 */
    private long currentCount;

    /** 上期请求数 */
    private long previousCount;
}
