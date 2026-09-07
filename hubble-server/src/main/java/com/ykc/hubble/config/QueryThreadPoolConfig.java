package com.ykc.hubble.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 线程池配置
 *
 * @author Cloud Eyes Team
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class QueryThreadPoolConfig {

    private final SlsConfig slsConfig;

    /**
     * SLS查询专用线程池
     */
    @Bean("queryExecutor")
    public Executor queryExecutor() {
        SlsConfig.ThreadPoolConfig config = slsConfig.getThreadPool();
        
        return new ThreadPoolExecutor(
                config.getCoreSize() != null ? config.getCoreSize() : 8,
                config.getMaxSize() != null ? config.getMaxSize() : 16,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity() != null ? config.getQueueCapacity() : 100),
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("sls-query-" + thread.getId());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
