package com.ykc.hubble.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 用户行为轨迹查询请求DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class UserBehaviorTraceQueryDTO {

    /**
     * 查询关键字
     */
    @NotBlank(message = "关键字不能为空")
    private String keyword;

    /**
     * 查询日期（yyyy-MM-dd格式），如果为空则使用时间范围
     */
    private String date;

    /**
     * 时间范围（Unix时间戳，秒），如果指定了date则忽略此字段
     */
    private TimeRange timeRange;

    /**
     * 查询偏移量（用于分页）
     */
    @jakarta.validation.constraints.Min(value = 0, message = "offset不能为负数")
    private Integer offset = 0;

    /**
     * 每页查询数量
     */
    @Positive(message = "limit必须大于0")
    private Integer limit = 100;

    @Data
    public static class TimeRange {
        /**
         * 开始时间（Unix时间戳，秒）
         */
        @NotNull(message = "开始时间不能为空")
        private Long from;

        /**
         * 结束时间（Unix时间戳，秒）
         */
        @NotNull(message = "结束时间不能为空")
        private Long to;
    }
}

