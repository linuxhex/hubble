package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务链路实体
 *
 * @author Cloud Eyes Team
 */
@Data
@TableName("business_trace")
public class BusinessTrace {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务名称（唯一）
     */
    @TableField("name")
    private String name;

    /**
     * 业务描述
     */
    @TableField("description")
    private String description;

    /**
     * 业务分类（字典值）
     */
    @TableField("category")
    private String category;

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

    /**
     * 关联的节点列表（不存储在数据库）
     */
    @TableField(exist = false)
    private List<TraceNode> nodes;
}
