package com.ykc.hubble.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * SLS关键字模版DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class SlsKeywordDTO {

    /**
     * 描述
     */
    @NotBlank(message = "描述不能为空")
    @Size(max = 500, message = "描述长度不能超过500字符")
    private String desc;

    /**
     * 描述向量（可选，如果不提供则从desc自动生成）
     */
    private List<Float> descVector;

    /**
     * 查询关键字
     */
    @NotBlank(message = "查询关键字不能为空")
    @Size(max = 1000, message = "查询关键字长度不能超过1000字符")
    private String keywords;

    /**
     * 归属应用
     */
    @Size(max = 100, message = "归属应用长度不能超过100字符")
    private String application;

    /**
     * SLSLogstore
     */
    @Size(max = 200, message = "SLSLogstore长度不能超过200字符")
    private String logstore;

    /**
     * 标签（多个标签以英文逗号分隔）
     */
    @Size(max = 500, message = "标签长度不能超过500字符")
    private String tags;
}

