package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("middleware_alert_config")
public class MiddlewareAlertConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("middleware_type")
    private String middlewareType;

    @TableField("instance_id")
    private String instanceId;

    @TableField("metric_name")
    private String metricName;

    @TableField("red_threshold")
    private BigDecimal redThreshold;

    @TableField("yellow_threshold")
    private BigDecimal yellowThreshold;

    @TableField("compare_type")
    private String compareType;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
