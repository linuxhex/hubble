package com.ykc.hubble.vo;

import lombok.Data;

/**
 * SLS关键字模版VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class SlsKeywordVO {

    /**
     * 主键ID
     */
    private String id;

    /**
     * 描述
     */
    private String desc;

    /**
     * 查询关键字
     */
    private String keywords;

    /**
     * 归属应用
     */
    private String application;

    /**
     * SLSLogstore
     */
    private String logstore;

    /**
     * 标签（多个标签以英文逗号分隔）
     */
    private String tags;
}

