package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.BusinessTrace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务链路Mapper
 *
 * @author Cloud Eyes Team
 */
@Mapper
public interface TraceMapper extends BaseMapper<BusinessTrace> {
}
