package com.ykc.hubble.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 链路节点VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class TraceNodeVO {

    /**
     * 节点ID
     */
    private Long id;

    /**
     * 父节点ID（为null表示顶级节点）
     */
    private Long parentId;

    /**
     * 是否有子节点
     */
    private Boolean hasChildren;

    /**
     * 节点名称
     */
    private String name;

    /**
     * 节点描述
     */
    private String description;

    /**
     * SLS日志库名称
     */
    private String slsLogstore;

    /**
     * 查询关键字模板
     */
    private String queryTemplate;

    /**
     * 节点顺序
     */
    private Integer nodeOrder;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
