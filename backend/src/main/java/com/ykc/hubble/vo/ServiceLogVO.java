package com.ykc.hubble.vo;

import lombok.Data;

/**
 * 下钻服务日志 VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class ServiceLogVO {

    /**
     * 日志时间（毫秒）
     */
    private long time;

    /**
     * 日志级别
     */
    private String level;

    /**
     * 服务名（容器名，缺失时回退到模板 application）
     */
    private String service;

    /**
     * 分布式 traceId
     */
    private String trace;

    /**
     * 日志内容
     */
    private String message;
}
