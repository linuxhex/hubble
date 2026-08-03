package com.ykc.hubble.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 链路节点DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class TraceNodeDTO {

    /**
     * 父节点ID（为null表示顶级节点）
     */
    private Long parentId;

    /**
     * 节点名称
     */
    @NotBlank(message = "节点名称不能为空")
    @Size(min = 1, max = 30, message = "节点名称长度必须在1-30之间")
    private String name;

    /**
     * 节点描述
     */
    @Size(max = 100, message = "描述长度不能超过100字符")
    private String description;

    /**
     * SLS日志库名称
     */
    @NotBlank(message = "SLS日志库名称不能为空")
    @Size(max = 100, message = "SLS日志库名称长度不能超过100字符")
    private String slsLogstore;

    /**
     * 查询关键字模板
     */
    @NotBlank(message = "查询模板不能为空")
    private String queryTemplate;

    /**
     * 节点顺序
     */
    @NotNull(message = "节点顺序不能为空")
    @Min(value = 0, message = "节点顺序必须大于等于0")
    private Integer nodeOrder;
}
