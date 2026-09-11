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

    @TableField("pid")
    private String pid;

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

    @TableField("gc_count")
    private Integer gcCount;

    @TableField("gc_time")
    private BigDecimal gcTime;

    @TableField("max_qps")
    private BigDecimal maxQps;

    @TableField("avg_rt")
    private BigDecimal avgRt;

    @TableField("total_count")
    private Long totalCount;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
