package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务依赖邻接表（依赖巡检扫描时沉淀，告警时反查上游调用方算爆炸半径）
 */
@Data
@TableName("service_dependency")
public class ServiceDependency {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 调用方应用 */
    @TableField("app_name")
    private String appName;

    /** 被依赖服务 */
    @TableField("depends_on")
    private String dependsOn;

    /** 调用类型（HTTP/Dubbo/HSF 等） */
    @TableField("rpc_type")
    private String rpcType;

    /** 最近一次巡检见到该依赖的时间(epoch ms) */
    @TableField(value = "last_seen_at", updateStrategy = FieldStrategy.ALWAYS)
    private Long lastSeenAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
