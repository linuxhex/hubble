package com.ykc.hubble.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ykc.hubble.vo.SnapshotPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Deque;

/**
 * 大盘缓存配置
 *
 * @author Cloud Eyes Team
 */
@Configuration
public class CaffeineConfig {

    /**
     * 大盘时序快照缓存：监控项ID -> 时序点队列
     */
    @Bean
    public Cache<Long, Deque<SnapshotPoint>> monitorSnapshotCache() {
        return Caffeine.newBuilder().maximumSize(5000).build();
    }
}
