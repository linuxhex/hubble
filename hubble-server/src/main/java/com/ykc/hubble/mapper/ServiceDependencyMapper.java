package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.ServiceDependency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ServiceDependencyMapper extends BaseMapper<ServiceDependency> {

    @Select("SELECT * FROM service_dependency WHERE app_name = #{appName} AND depends_on = #{dependsOn} AND rpc_type = #{rpcType}")
    ServiceDependency selectByAppAndDepends(@Param("appName") String appName, @Param("dependsOn") String dependsOn,
                                            @Param("rpcType") String rpcType);

    @Select("SELECT * FROM service_dependency WHERE depends_on = #{dependsOn} ORDER BY last_seen_at DESC LIMIT 50")
    List<ServiceDependency> selectUpstream(@Param("dependsOn") String dependsOn);
}
