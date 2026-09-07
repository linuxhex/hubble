package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 链路节点实体
 *
 * @author Cloud Eyes Team
 */
@Data
@TableName("trace_node")
public class TraceNode {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属业务链路ID
     */
    @TableField("trace_id")
    private Long traceId;

    /**
     * 父节点ID（为null表示顶级节点）
     */
    @TableField("parent_id")
    private Long parentId;

    /**
     * 节点名称
     */
    @TableField("name")
    private String name;

    /**
     * 节点描述
     */
    @TableField("description")
    private String description;

    /**
     * SLS日志库名称
     */
    @TableField("sls_logstore")
    private String slsLogstore;

    /**
     * 查询关键字模板（支持变量占位符）
     */
    @TableField("query_template")
    private String queryTemplate;

    /**
     * 节点顺序（用于展示排序）
     */
    @TableField("node_order")
    private Integer nodeOrder;

    /**
     * 删除标记（0-未删除，1-已删除）
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
