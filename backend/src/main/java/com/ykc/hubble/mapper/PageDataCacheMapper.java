package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.PageDataCache;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * 页面数据缓存 Mapper
 *
 * @author Hubble Team
 */
@Mapper
public interface PageDataCacheMapper extends BaseMapper<PageDataCache> {

    /**
     * 删除过期缓存数据（expires_at < 当前时间）
     */
    @Delete("DELETE FROM page_data_cache WHERE expires_at < NOW()")
    int deleteExpired();
}
