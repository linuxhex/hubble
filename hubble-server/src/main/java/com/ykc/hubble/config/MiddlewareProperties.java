package com.ykc.hubble.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "middleware")
public class MiddlewareProperties {

    private MiddlewareGroup rocketmq = new MiddlewareGroup();
    private MiddlewareGroup kafka = new MiddlewareGroup();
    private MiddlewareGroup lindorm = new MiddlewareGroup();
    private MiddlewareGroup elasticsearch = new MiddlewareGroup();
    private OssGroup oss = new OssGroup();

    @Data
    public static class MiddlewareGroup {
        private List<InstanceConfig> instances = new ArrayList<>();
    }

    @Data
    public static class OssGroup {
        private List<OssBucketConfig> buckets = new ArrayList<>();
    }

    @Data
    public static class InstanceConfig {
        private String instanceId;
        private String instanceName;
        private String topic;
    }

    @Data
    public static class OssBucketConfig {
        private String bucketName;
        private String instanceName;
    }
}
