package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.TraceNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 链路节点Mapper
 *
 * @author Cloud Eyes Team
 */
@Mapper
public interface TraceNodeMapper extends BaseMapper<TraceNode> {
    
    /**
     * 物理删除指定链路ID的所有节点
     *
     * @param traceId 业务链路ID
     */
    @Delete("DELETE FROM trace_node WHERE trace_id = #{traceId}")
    void physicalDeleteByTraceId(@Param("traceId") Long traceId);
}
