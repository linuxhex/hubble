package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykc.hubble.entity.PageDataCache;
import com.ykc.hubble.mapper.PageDataCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 页面数据缓存服务：提供缓存的读取、写入、清理功能
 * 缓存数据存储在数据库中，保留1天，超过1天自动清理
 *
 * @author Hubble Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageDataCacheService {

    private final PageDataCacheMapper pageDataCacheMapper;
    private final ObjectMapper objectMapper;

    /** 写信号量：限制并发写 H2 的连接数，避免并行查询耗尽连接池 */
    private static final Semaphore WRITE_SEM = new Semaphore(5);

    /**
     * 缓存保留时间：1天
     */
    private static final int CACHE_RETENTION_HOURS = 24;

    /**
     * 读取缓存数据
     *
     * @param pageKey 页面标识
     * @param dataKey 数据标识
     * @param clazz   数据类型
     * @return 缓存的数据，如果不存在或已过期则返回 null
     */
    public <T> T get(String pageKey, String dataKey, Class<T> clazz) {
        try {
            LambdaQueryWrapper<PageDataCache> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PageDataCache::getPageKey, pageKey)
                   .eq(PageDataCache::getDataKey, dataKey)
                   .gt(PageDataCache::getExpiresAt, LocalDateTime.now());

            PageDataCache cache = pageDataCacheMapper.selectOne(wrapper);
            if (cache == null || cache.getDataContent() == null) {
                return null;
            }

            log.debug("缓存命中: pageKey={}, dataKey={}", pageKey, dataKey);
            return objectMapper.readValue(cache.getDataContent(), clazz);
        } catch (Exception e) {
            log.warn("读取缓存失败: pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
            return null;
        }
    }

    /**
     * 读取缓存数据（使用 TypeReference，支持泛型类型如 List）
     *
     * @param pageKey       页面标识
     * @param dataKey       数据标识
     * @param typeReference 类型引用
     * @return 缓存的数据，如果不存在或已过期则返回 null
     */
    public <T> T get(String pageKey, String dataKey, TypeReference<T> typeReference) {
        try {
            LambdaQueryWrapper<PageDataCache> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PageDataCache::getPageKey, pageKey)
                   .eq(PageDataCache::getDataKey, dataKey)
                   .gt(PageDataCache::getExpiresAt, LocalDateTime.now());

            PageDataCache cache = pageDataCacheMapper.selectOne(wrapper);
            if (cache == null || cache.getDataContent() == null) {
                return null;
            }

            log.debug("缓存命中(TypeReference): pageKey={}, dataKey={}", pageKey, dataKey);
            return objectMapper.readValue(cache.getDataContent(), typeReference);
        } catch (Exception e) {
            log.warn("读取缓存失败(TypeReference): pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
            return null;
        }
    }

    /**
     * 读取缓存的原始 JSON 字符串
     *
     * @param pageKey 页面标识
     * @param dataKey 数据标识
     * @return 缓存的 JSON 字符串，如果不存在或已过期则返回 null
     */
    public String getRaw(String pageKey, String dataKey) {
        try {
            LambdaQueryWrapper<PageDataCache> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PageDataCache::getPageKey, pageKey)
                   .eq(PageDataCache::getDataKey, dataKey)
                   .gt(PageDataCache::getExpiresAt, LocalDateTime.now());

            PageDataCache cache = pageDataCacheMapper.selectOne(wrapper);
            if (cache == null || cache.getDataContent() == null) {
                return null;
            }

            log.debug("缓存命中(Raw): pageKey={}, dataKey={}", pageKey, dataKey);
            return cache.getDataContent();
        } catch (Exception e) {
            log.warn("读取缓存失败(Raw): pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
            return null;
        }
    }

    /**
     * 读取缓存数据（List类型）
     *
     * @param pageKey     页面标识
     * @param dataKey     数据标识
     * @param elementClass 列表元素类型
     * @return 缓存的数据列表，如果不存在或已过期则返回 null
     */
    public <T> List<T> getList(String pageKey, String dataKey, Class<T> elementClass) {
        try {
            LambdaQueryWrapper<PageDataCache> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PageDataCache::getPageKey, pageKey)
                   .eq(PageDataCache::getDataKey, dataKey)
                   .gt(PageDataCache::getExpiresAt, LocalDateTime.now());

            PageDataCache cache = pageDataCacheMapper.selectOne(wrapper);
            if (cache == null || cache.getDataContent() == null) {
                return null;
            }

            log.debug("缓存命中(List): pageKey={}, dataKey={}", pageKey, dataKey);
            var listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, elementClass);
            return objectMapper.readValue(cache.getDataContent(), listType);
        } catch (Exception e) {
            log.warn("读取缓存失败(List): pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
            return null;
        }
    }

    /**
     * 保存缓存数据
     *
     * @param pageKey 页面标识
     * @param dataKey 数据标识
     * @param data    要缓存的数据
     */
    public void save(String pageKey, String dataKey, Object data) {
        save(pageKey, dataKey, data, CACHE_RETENTION_HOURS * 60);
    }

    /**
     * 保存缓存数据（自定义过期时间）
     *
     * @param pageKey      页面标识
     * @param dataKey      数据标识
     * @param data         要缓存的数据
     * @param ttlMinutes   缓存有效时间（分钟）
     */
    public void save(String pageKey, String dataKey, Object data, int ttlMinutes) {
        try {
            String jsonContent = objectMapper.writeValueAsString(data);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiresAt = now.plusMinutes(ttlMinutes);

            if (!WRITE_SEM.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("缓存写入跳过（信号量满）: pageKey={}, dataKey={}", pageKey, dataKey);
                return;
            }
            try {
                pageDataCacheMapper.mergeInto(pageKey, dataKey, jsonContent, now, expiresAt);
                log.debug("缓存保存: pageKey={}, dataKey={}", pageKey, dataKey);
            } finally {
                WRITE_SEM.release();
            }
        } catch (JsonProcessingException e) {
            log.error("序列化缓存数据失败: pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
        } catch (Exception e) {
            log.warn("保存缓存失败: pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
        }
    }

    /**
     * 清理过期缓存数据
     *
     * @return 清理的记录数
     */
    public int cleanExpired() {
        try {
            int deleted = pageDataCacheMapper.deleteExpired();
            if (deleted > 0) {
                log.info("清理过期缓存: {} 条", deleted);
            }
            return deleted;
        } catch (Exception e) {
            log.error("清理过期缓存失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 删除指定页面的缓存
     *
     * @param pageKey 页面标识
     * @param dataKey 数据标识（可选，为null时删除该页面所有缓存）
     */
    public void evict(String pageKey, String dataKey) {
        try {
            LambdaQueryWrapper<PageDataCache> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PageDataCache::getPageKey, pageKey);
            if (dataKey != null) {
                wrapper.eq(PageDataCache::getDataKey, dataKey);
            }
            int deleted = pageDataCacheMapper.delete(wrapper);
            log.info("删除缓存: pageKey={}, dataKey={}, 删除 {} 条", pageKey, dataKey, deleted);
        } catch (Exception e) {
            log.error("删除缓存失败: pageKey={}, dataKey={}, error={}", pageKey, dataKey, e.getMessage());
        }
    }
}
