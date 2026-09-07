package com.ykc.hubble.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * SLS关键字模版实体
 *
 * @author Cloud Eyes Team
 */
@Data
public class SlsKeyword implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private String id;

    /** 描述 */
    private String desc;

    /** 描述向量 */
    private List<Float> descVector;

    /** 查询关键字 */
    private String keywords;

    /** 归属应用 */
    private String application;

    /** SLSLogstore */
    private String logstore;

    /** 标签 */
    private String tags;
}

