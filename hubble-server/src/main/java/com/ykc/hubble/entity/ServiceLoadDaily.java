package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("service_load_daily")
public class ServiceLoadDaily {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("app_name")
    private String appName;

    @TableField("stat_date")
    private LocalDate statDate;

    @TableField("avg_cpu")
    private BigDecimal avgCpu;

    @TableField("max_cpu")
    private BigDecimal maxCpu;

    @TableField("avg_memory")
    private BigDecimal avgMemory;

    @TableField("max_memory")
    private BigDecimal maxMemory;

    @TableField("max_qps")
    private BigDecimal maxQps;

    @TableField("avg_rt")
    private BigDecimal avgRt;

    @TableField("total_count")
    private Long totalCount;

    /** 1=当日12点采集仅半天数据 */
    @TableField("partial_day")
    private Integer partialDay;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
