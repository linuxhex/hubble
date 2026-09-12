package com.ykc.hubble.vo;

import lombok.Data;

/**
 * 用户行为轨迹单条记录VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class UserBehaviorTraceItemVO {

    /**
     * 页面名称
     */
    private String pageName;

    /**
     * 页面ID
     */
    private String pageid;

    /**
     * 终端类型
     */
    private String terminal;

    /**
     * 操作类型
     */
    private String type;

    /**
     * 用户账号
     */
    private String userAccount;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 来源页面
     */
    private String fromPage;

    /**
     * 应用版本
     */
    private String appVersion;

    /**
     * AB测试值
     */
    private String abValue;

    /**
     * 操作时间（毫秒时间戳）- 前端埋点时间
     */
    private Long dateTime;

    /**
     * 格式化后的时间字符串（yyyy-MM-dd HH:mm:ss.SSS）- 前端埋点时间
     */
    private String formattedDateTime;

    /**
     * 格式化后的日志时间字符串（yyyy-MM-dd HH:mm:ss.SSS）
     */
    private String formattedLogTime;

    /**
     * URL地址
     */
    private String url;

    /**
     * 追踪ID
     */
    private String trace;

    /**
     * 后台接口响应码
     */
    private String responseStatus;

    /**
     * 响应参数（JSON字符串）
     */
    private String responseData;

    /**
     * 服务名称（后端服务日志）
     */
    private String serviceName;

    /**
     * 接口路径（后端服务日志）
     */
    private String api;

    /**
     * 客户端IP（后端服务日志）
     */
    private String clientIp;

    /**
     * 原始日志消息
     */
    private String logMessage;

    /**
     * 日志级别
     */
    private String logLevel;
}

