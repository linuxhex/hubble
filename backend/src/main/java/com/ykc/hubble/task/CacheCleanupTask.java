package com.ykc.hubble.task;

import com.ykc.hubble.service.PageDataCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 缓存清理定时任务：每小时执行一次，清理超过1天的缓存数据
 *
 * @author Hubble Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheCleanupTask {

    private final PageDataCacheService pageDataCacheService;

    /**
     * 每小时清理一次过期缓存
     */
    @Scheduled(fixedRate = 3600000) // 1小时 = 3600000毫秒
    public void cleanExpiredCache() {
        log.debug("开始执行缓存清理任务...");
        int deleted = pageDataCacheService.cleanExpired();
        if (deleted > 0) {
            log.info("缓存清理任务完成，清理 {} 条过期数据", deleted);
        }
    }
}
