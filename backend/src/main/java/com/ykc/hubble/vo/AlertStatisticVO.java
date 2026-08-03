package com.ykc.hubble.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 监控统计 VO（对齐前端 getAlertStatistics 契约）
 *
 * @author Cloud Eyes Team
 */
@Data
public class AlertStatisticVO {

    /**
     * 当前日志命中量（timeRange 内最新采集点）
     */
    private long currentLogCount;

    /**
     * 今日峰值
     */
    private long todayMax;

    /**
     * 今日均值
     */
    private double todayAvg;

    /**
     * 告警阈值（回显配置）
     */
    private Integer alertThreshold;

    /**
     * 今日告警次数（达到阈值的采集点数）
     */
    private long todayAlertCount;

    /**
     * 健康度：GREEN / YELLOW / RED / GRAY
     */
    private String status;

    /**
     * 分项统计（P0：按健康度归类今日采集点）
     */
    private List<Map<String, Object>> detailStatistics;
}
