package com.ykc.hubble.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云SLS配置
 *
 * @author Cloud Eyes Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.sls")
public class SlsConfig {

    /**
     * AccessKey ID
     */
    private String accessKeyId;

    /**
     * AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * Endpoint
     */
    private String endpoint;

    /**
     * SLS项目名称（全局统一）
     */
    private String project;

    /**
     * 查询配置
     */
    private QueryConfig query = new QueryConfig();

    /**
     * 线程池配置
     */
    private ThreadPoolConfig threadPool = new ThreadPoolConfig();

    public ThreadPoolConfig getThreadPool() {
        return threadPool;
    }

    public void setThreadPool(ThreadPoolConfig threadPool) {
        this.threadPool = threadPool;
    }

    @Data
    public static class QueryConfig {
        /**
         * 单个节点最大返回日志数
         */
        private Integer maxResults = 1000;

        /**
         * 单个节点查询超时时间（秒）
         */
        private Integer timeoutSeconds = 10;

        /**
         * 全局查询超时时间（秒）
         */
        private Integer globalTimeoutSeconds = 15;

        /**
         * 查询失败重试次数
         */
        private Integer retryTimes = 3;
    }

    @Data
    public static class ThreadPoolConfig {
        /**
         * 核心线程数
         */
        private Integer coreSize = 8;

        /**
         * 最大线程数
         */
        private Integer maxSize = 16;

        /**
         * 队列容量
         */
        private Integer queueCapacity = 100;

        public Integer getCoreSize() {
            return coreSize;
        }

        public Integer getMaxSize() {
            return maxSize;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }
    }
}
