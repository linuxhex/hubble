package com.ykc.hubble.vo;

import lombok.Data;

/**
 * 查询变量VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class QueryVariableVO {

    /**
     * 变量名
     */
    private String name;

    /**
     * 变量类型（string/number/date）
     */
    private String type;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 默认值（可选）
     */
    private String defaultValue;
}

