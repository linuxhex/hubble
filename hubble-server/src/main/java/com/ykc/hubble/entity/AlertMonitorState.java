package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 监控项告警评估状态（持久化到 H2，重启后恢复红盘防抖计数/冷却时间/恢复计数）
 */
@Data
@TableName("alert_monitor_state")
public class AlertMonitorState {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("config_id")
    private Long configId;

    @TableField("consecutive_red_count")
    private Integer consecutiveRedCount;

    @TableField("consecutive_normal_count")
    private Integer consecutiveNormalCount;

    @TableField("last_alert_time")
    private Long lastAlertTime;

    @TableField("last_status")
    private String lastStatus;

    @TableField("last_collect_at")
    private Long lastCollectAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
