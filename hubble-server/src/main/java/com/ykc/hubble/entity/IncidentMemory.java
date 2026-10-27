package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 事故调查记录（告警驱动的根因分析报告 + 历史事故记忆）
 */
@Data
@TableName("incident_memory")
public class IncidentMemory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 告警来源：ARMS/DEP_RT/DEP_ERR/MW */
    @TableField("alert_source")
    private String alertSource;

    /** 告警类型（alertname 或规则名） */
    @TableField("alert_type")
    private String alertType;

    @TableField("alert_name")
    private String alertName;

    /** 涉及服务/实例 */
    @TableField("service_name")
    private String serviceName;

    @TableField("fingerprint")
    private String fingerprint;

    /** 去重键（fingerprint 或 来源|名称|服务|30min桶） */
    @TableField("dedup_key")
    private String dedupKey;

    @TableField("severity")
    private String severity;

    @TableField("alert_value")
    private String alertValue;

    /** INVESTIGATING/DONE/FAILED */
    @TableField("status")
    private String status;

    @TableField("root_cause")
    private String rootCause;

    @TableField("analysis_summary")
    private String analysisSummary;

    @TableField("recommendation")
    private String recommendation;

    /** AI 置信度 0-1 */
    @TableField("confidence")
    private BigDecimal confidence;

    /** 四路取证证据（脱敏后 JSON） */
    @TableField("evidence_json")
    private String evidenceJson;

    /** 调查失败原因 */
    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    /** 告警是否已恢复 */
    @TableField(value = "resolved", updateStrategy = FieldStrategy.ALWAYS)
    private Integer resolved;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
