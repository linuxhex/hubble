package com.ykc.hubble.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 运维大盘配置
 *
 * @author Cloud Eyes Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties {

    /**
     * 快照采集扫描周期（秒）：每隔该周期扫描一次启用的监控项，对到点者执行采集
     */
    private int scanIntervalSeconds = 5;

    /**
     * 每个监控项保留的时序点数（按采集间隔 60s 估，1440 点约覆盖一天）
     */
    private int snapshotRetention = 1440;

    /**
     * 默认 SLS 查询 logstore（当模板未指定时使用）
     */
    private String defaultQueryLogstore = "all";

    /**
     * 错误分析单次拉取日志行数
     */
    private int errorScanLines = 200;

    /**
     * 分钟时间线红盘阈值（某分钟某服务错误数达到即判红）
     */
    private int minuteRedThreshold = 50;

    /**
     * 分钟时间线粉盘阈值
     */
    private int minuteYellowThreshold = 20;

    /**
     * 前端大盘地址（告警通知中附带看板链接）
     */
    private String dashboardUrl = "http://localhost:5173";
}
