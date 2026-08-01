package com.ykc.cloudeyes.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务链路详情VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class BusinessTraceDetailVO {

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
     * 节点列表
     */
    private List<TraceNodeVO> nodes;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
