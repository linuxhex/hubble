package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警规则与钉钉机器人的绑定关系（多对多联合主键）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("alert_config_robot")
public class AlertConfigRobot {

    private Long alertConfigId;

    private Long robotId;
}
