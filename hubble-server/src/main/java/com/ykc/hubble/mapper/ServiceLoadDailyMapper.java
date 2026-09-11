package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.ServiceLoadDaily;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ServiceLoadDailyMapper extends BaseMapper<ServiceLoadDaily> {

    @Delete("DELETE FROM service_load_daily WHERE stat_date < #{beforeDate}")
    int deleteBeforeDate(@Param("beforeDate") LocalDate beforeDate);

    @Select("SELECT DISTINCT app_name FROM service_load_daily ORDER BY app_name")
    List<String> selectDistinctAppNames();

    @Select("SELECT * FROM service_load_daily WHERE app_name = #{appName} AND stat_date >= #{startDate} ORDER BY stat_date")
    List<ServiceLoadDaily> selectByAppNameAndDateRange(@Param("appName") String appName, @Param("startDate") LocalDate startDate);

    @Select("SELECT * FROM service_load_daily WHERE stat_date >= #{startDate} ORDER BY app_name, stat_date")
    List<ServiceLoadDaily> selectAllByDateRange(@Param("startDate") LocalDate startDate);
}
