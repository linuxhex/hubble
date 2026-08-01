package com.ykc.cloudeyes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.cloudeyes.entity.BusinessTrace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务链路Mapper
 *
 * @author Cloud Eyes Team
 */
@Mapper
public interface TraceMapper extends BaseMapper<BusinessTrace> {
}
