package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.IncidentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IncidentMemoryMapper extends BaseMapper<IncidentMemory> {

    @Select("SELECT * FROM incident_memory WHERE dedup_key = #{dedupKey} " +
            "AND created_at > #{since} ORDER BY id DESC LIMIT 1")
    IncidentMemory selectRecentByDedupKey(@Param("dedupKey") String dedupKey, @Param("since") java.time.LocalDateTime since);

    @Select("SELECT * FROM incident_memory WHERE alert_type = #{alertType} AND service_name = #{serviceName} " +
            "AND status = 'DONE' ORDER BY created_at DESC LIMIT #{limit}")
    List<IncidentMemory> selectSimilarBoth(@Param("alertType") String alertType, @Param("serviceName") String serviceName, @Param("limit") int limit);

    @Select("SELECT * FROM incident_memory WHERE alert_type = #{alertType} " +
            "AND status = 'DONE' ORDER BY created_at DESC LIMIT #{limit}")
    List<IncidentMemory> selectSimilarByType(@Param("alertType") String alertType, @Param("limit") int limit);

    @Select("SELECT * FROM incident_memory WHERE service_name = #{serviceName} " +
            "AND status = 'DONE' ORDER BY created_at DESC LIMIT #{limit}")
    List<IncidentMemory> selectSimilarByService(@Param("serviceName") String serviceName, @Param("limit") int limit);
}
