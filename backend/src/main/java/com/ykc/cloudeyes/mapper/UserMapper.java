package com.ykc.cloudeyes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.cloudeyes.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户Mapper
 *
 * @author Cloud Eyes Team
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}

