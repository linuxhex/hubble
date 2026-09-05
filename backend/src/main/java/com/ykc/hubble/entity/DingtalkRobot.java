package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钉钉机器人配置实体：一个机器人对应一个钉钉群的 Webhook。
 * 告警规则可绑定多个机器人，实现一条告警通知到多个群。
 */
@Data
@TableName("dingtalk_robot")
public class DingtalkRobot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 机器人名称（如"运维群机器人"） */
    @TableField("name")
    private String name;

    /** 钉钉机器人 Webhook 地址 */
    @TableField("webhook")
    private String webhook;

    /** 加签密钥（选填） */
    @TableField("secret")
    private String secret;

    /** 备注（如"通知到运维群"） */
    @TableField("remark")
    private String remark;

    /** 是否启用 */
    @TableField("enabled")
    private Integer enabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
