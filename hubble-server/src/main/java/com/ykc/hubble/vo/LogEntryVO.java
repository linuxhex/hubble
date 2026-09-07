package com.ykc.hubble.vo;

import lombok.Data;

import java.util.Map;

/**
 * 日志条目VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class LogEntryVO {

    /**
     * 日志级别
     */
    private String level;

    /**
     * 日志行内容
     */
    private String line;

    /**
     * 日志消息
     */
    private String message;

    /**
     * 链路追踪ID
     */
    private String trace;

    /**
     * 时间
     */
    private String time;

    /**
     * 容器IP
     */
    private String containerIp;

    /**
     * 其他字段
     */
    private Map<String, String> fields;
}
