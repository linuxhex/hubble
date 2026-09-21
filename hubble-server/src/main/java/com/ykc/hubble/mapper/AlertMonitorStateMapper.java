package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.AlertMonitorState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AlertMonitorStateMapper extends BaseMapper<AlertMonitorState> {

    @Select("SELECT * FROM alert_monitor_state WHERE config_id = #{configId}")
    AlertMonitorState selectByConfigId(@Param("configId") Long configId);
}
