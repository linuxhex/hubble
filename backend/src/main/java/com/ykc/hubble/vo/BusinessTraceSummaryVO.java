package com.ykc.hubble.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务链路摘要VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class BusinessTraceSummaryVO {

    /**
     * 业务链路ID
     */
    private Long id;

    /**
     * 业务名称
     */
    private String name;

    /**
     * 业务描述
     */
    private String description;

    /**
     * 业务分类（字典值）
     */
    private String category;

    /**
     * 业务分类名称
     */
    private String categoryName;

    /**
     * 节点数量
     */
    private Integer nodeCount;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
