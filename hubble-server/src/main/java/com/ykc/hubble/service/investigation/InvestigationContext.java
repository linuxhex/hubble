package com.ykc.hubble.service.investigation;

import lombok.Builder;
import lombok.Data;

/**
 * 事故调查上下文（告警摘要信息，由各告警源钩子组装）
 */
@Data
@Builder
public class InvestigationContext {

    /** 告警来源：ARMS/DEP_RT/DEP_ERR/MW */
    private String alertSource;

    /** 告警类型（alertname 或规则名） */
    private String alertType;

    /** 告警名称 */
    private String alertName;

    /** 涉及服务/实例 */
    private String serviceName;

    private String severity;

    /** 告警当前值（如 RT、错误率） */
    private String alertValue;

    /** 告警描述（清洗后） */
    private String message;

    private String fingerprint;

    /** 告警触发时间（epoch ms） */
    private Long alertTimeMs;
}
