package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务告警阈值配置：接口劣化/流量暴涨/红黄盘等阈值统一管理，替代硬编码。
 */
@Data
@TableName("alert_threshold_config")
public class AlertThresholdConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 配置键，如 degradation_threshold */
    @TableField("config_key")
    private String configKey;

    /** 配置值 */
    @TableField("config_value")
    private String configValue;

    /** 说明 */
    @TableField("description")
    private String description;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
