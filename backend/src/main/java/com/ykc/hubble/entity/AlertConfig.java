package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 日志监控配置实体：定义一条监控项（绑定 SLS 模板、采集间隔、告警阈值）
 *
 * @author Cloud Eyes Team
 */
@Data
@TableName("alert_config")
public class AlertConfig {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 监控标题
     */
    @TableField("title")
    private String title;

    /**
     * 备注说明
     */
    @TableField("description")
    private String description;

    /**
     * 关联 Milvus SlsKeyword 模板ID（取 application/logstore/keywords）
     */
    @TableField("keyword_template_id")
    private String keywordTemplateId;

    /**
     * 每日监控开始时间 HH:mm:ss
     */
    @TableField("start_time")
    private String startTime;

    /**
     * 每日监控结束时间 HH:mm:ss
     */
    @TableField("end_time")
    private String endTime;

    /**
     * 采集间隔（秒）
     */
    @TableField("collection_interval")
    private Integer collectionInterval;

    /**
     * 告警阈值（日志命中量达到该值视为红盘告警）
     */
    @TableField("alert_threshold")
    private Integer alertThreshold;

    /**
     * 黄盘阈值比例（相对红盘阈值，如 0.5 表示达到红盘阈值的 50% 即触发黄盘）
     */
    @TableField("yellow_threshold_ratio")
    private Double yellowThresholdRatio;

    /**
     * 高峰时段开始时间 HH:mm:ss（可选）
     */
    @TableField("peak_start_time")
    private String peakStartTime;

    /**
     * 高峰时段结束时间 HH:mm:ss（可选）
     */
    @TableField("peak_end_time")
    private String peakEndTime;

    /**
     * 高峰时段告警阈值（覆盖 alert_threshold，可选）
     */
    @TableField("peak_alert_threshold")
    private Integer peakAlertThreshold;

    /**
     * 告警通知 webhook（钉钉机器人，选填）
     */
    @TableField("alert_webhook")
    private String alertWebhook;

    /**
     * 是否启用
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 删除标记（0-未删除，1-已删除）
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
