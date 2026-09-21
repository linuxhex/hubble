package com.ykc.hubble.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 服务扩容评估结果
 */
@Data
public class ServiceAssessmentVO {

    private String appName;

    /** 参与评估的完整样本数 */
    private int sampleCount;
    /** 首尾完整样本跨越天数 */
    private int coverageDays;
    /** 数据完整度(%) */
    private int completeness;
    /** 最新一天是否半天数据 1=是 */
    private Integer partialToday;
    /** 最新样本日期（含半天样本），null=无数据 */
    private String latestStatDate;
    /** 最新样本距今天数，0=当天；≥2 说明采集滞后 */
    private Integer latestDataAgeDays;

    private BigDecimal cpuPeak;
    private BigDecimal memPeak;
    private BigDecimal qpsPeak;
    /** green/yellow/red，null=无数据 */
    private String cpuWaterLevel;
    private String memWaterLevel;

    /** 末7天 vs 前7天 环比(%)，null=样本不足 */
    private BigDecimal cpuGrowthPct;
    private BigDecimal memGrowthPct;
    private BigDecimal qpsGrowthPct;

    private BigDecimal cpuSlopePerDay;
    private BigDecimal memSlopePerDay;
    /** 距红线阈值天数，0=已达阈值，null=平稳/不可预测 */
    private Integer cpuDaysToThreshold;
    private Integer memDaysToThreshold;
    /** ≈N天后触顶 / 已达阈值 / 平稳 / 数据不足 */
    private String prediction;
    /** HIGH/MEDIUM/LOW */
    private String confidence;

    /** URGENT/SUGGEST/WATCH/NORMAL/INSUFFICIENT */
    private String adviceLevel;
    private String adviceText;
    /** 扩容目标指标：CPU / 内存 / CPU+内存 / QPS，NORMAL 为 null */
    private String adviceTargets;
    private String insufficientReason;
}
