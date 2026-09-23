package com.ykc.hubble.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ykc.hubble.entity.PageDataCache;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 原子 upsert：按 (page_key, data_key) 合并写入，消除 select-then-insert/update 的竞态和双倍连接占用
     */
    @org.apache.ibatis.annotations.Insert(
            "MERGE INTO page_data_cache (page_key, data_key, data_content, created_at, expires_at) "
            + "KEY (page_key, data_key) "
            + "VALUES (#{pageKey}, #{dataKey}, #{dataContent}, #{createdAt}, #{expiresAt})")
    int mergeInto(@Param("pageKey") String pageKey,
                  @Param("dataKey") String dataKey,
                  @Param("dataContent") String dataContent,
                  @Param("createdAt") java.time.LocalDateTime createdAt,
                  @Param("expiresAt") java.time.LocalDateTime expiresAt);
}
