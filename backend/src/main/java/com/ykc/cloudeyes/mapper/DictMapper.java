package com.ykc.cloudeyes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.cloudeyes.entity.SysDict;
import org.apache.ibatis.annotations.Mapper;

/**
 * 字典Mapper
 *
 * @author Cloud Eyes Team
 */
@Mapper
public interface DictMapper extends BaseMapper<SysDict> {
}

