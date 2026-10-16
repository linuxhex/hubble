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

    // 可空字段需强制写 null（updateById 默认忽略 null，会导致恢复后旧告警状态残留 H2，
    // 重启 init() 恢复出过期状态误发"持续数小时"的恢复通知）
    @TableField(value = "last_alert_time", updateStrategy = FieldStrategy.ALWAYS)
    private Long lastAlertTime;

    @TableField(value = "alert_start_time", updateStrategy = FieldStrategy.ALWAYS)
    private Long alertStartTime;

    @TableField(value = "last_status", updateStrategy = FieldStrategy.ALWAYS)
    private String lastStatus;

    @TableField(value = "last_collect_at", updateStrategy = FieldStrategy.ALWAYS)
    private Long lastCollectAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
