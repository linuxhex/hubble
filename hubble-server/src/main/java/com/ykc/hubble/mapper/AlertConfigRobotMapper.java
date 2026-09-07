package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.AlertConfigRobot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 告警规则与机器人绑定 Mapper
 */
public interface AlertConfigRobotMapper extends BaseMapper<AlertConfigRobot> {

    /** 查告警规则绑定的机器人 ID */
    @Select("SELECT robot_id FROM alert_config_robot WHERE alert_config_id = #{configId}")
    List<Long> selectRobotIdsByConfigId(Long configId);

    /** 删除告警规则的所有绑定 */
    @Delete("DELETE FROM alert_config_robot WHERE alert_config_id = #{configId}")
    int deleteByConfigId(Long configId);
}
