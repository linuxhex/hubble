package com.ykc.cloudeyes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 业务链路DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class BusinessTraceDTO {

    /**
     * 业务名称
     */
    @NotBlank(message = "业务名称不能为空")
    @Size(min = 1, max = 50, message = "业务名称长度必须在1-50之间")
    private String name;

    /**
     * 业务描述
     */
    @Size(max = 200, message = "描述长度不能超过200字符")
    private String description;

    /**
     * 业务分类（字典值）
     */
    @NotBlank(message = "业务分类不能为空")
    private String category;

    /**
     * 节点列表
     */
    @NotEmpty(message = "至少需要配置一个节点")
    @Valid
    private List<TraceNodeDTO> nodes;
}
