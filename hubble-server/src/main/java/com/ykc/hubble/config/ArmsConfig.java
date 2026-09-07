package com.ykc.hubble.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 ARMS（应用实时监控）配置
 *
 * @author Hubble Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.arms")
public class ArmsConfig {

    /**
     * AccessKey ID（可复用 SLS 同一套 AK）
     */
    private String accessKeyId;

    /**
     * AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * Region（ARMS 应用所在区域，默认 cn-hangzhou）
     */
    private String region = "cn-hangzhou";
}
