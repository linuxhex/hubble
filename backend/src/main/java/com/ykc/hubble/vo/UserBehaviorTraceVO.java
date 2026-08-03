package com.ykc.hubble.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户行为轨迹查询响应VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class UserBehaviorTraceVO {

    /**
     * 轨迹记录列表
     */
    private List<UserBehaviorTraceItemVO> items;

    /**
     * 总记录数（当前查询到的总数）
     */
    private Integer total;

    /**
     * 是否还有更多数据
     */
    private Boolean hasMore;
}

