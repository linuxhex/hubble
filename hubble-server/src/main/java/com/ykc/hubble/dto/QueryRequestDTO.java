package com.ykc.hubble.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 查询请求DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class QueryRequestDTO {

    /**
     * 业务链路ID
     */
    @NotNull(message = "业务链路ID不能为空")
    private Long traceId;

    /**
     * 查询变量值
     */
    @NotNull(message = "查询变量不能为空")
    private Map<String, String> variables;

    /**
     * 时间范围
     */
    @NotNull(message = "时间范围不能为空")
    private TimeRange timeRange;

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
