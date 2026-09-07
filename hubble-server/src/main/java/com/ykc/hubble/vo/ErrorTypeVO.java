package com.ykc.hubble.vo;

import lombok.Data;

/**
 * 错误类型 VO（对齐前端 getTopErrorTypes 契约）
 *
 * @author Cloud Eyes Team
 */
@Data
public class ErrorTypeVO {

    /**
     * 错误类型名（异常类名或日志特征）
     */
    private String typeName;

    /**
     * 出现次数
     */
    private long count;

    /**
     * 占比（%）
     */
    private double percentage;

    /**
     * 环比增长率（%，P0 暂为 0）
     */
    private double growthRate;

    /**
     * 分类（空指针/超时/资源/其他）
     */
    private String category;

    /**
     * 首次出现时间（毫秒）
     */
    private Long firstSeenAt;

    /**
     * 样本日志
     */
    private String sampleLog;
}
