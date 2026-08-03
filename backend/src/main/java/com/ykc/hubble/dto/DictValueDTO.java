package com.ykc.hubble.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字典值DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class DictValueDTO {

    /**
     * 字典值
     */
    @NotBlank(message = "字典值不能为空")
    @Size(max = 100, message = "字典值长度不能超过100字符")
    private String value;

    /**
     * 字典展示值（可选，如果不提供则使用value）
     */
    @Size(max = 100, message = "字典展示值长度不能超过100字符")
    private String label;
}

