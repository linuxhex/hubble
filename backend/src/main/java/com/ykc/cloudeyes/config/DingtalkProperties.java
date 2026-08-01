package com.ykc.cloudeyes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 钉钉配置
 *
 * @author Cloud Eyes Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "dingtalk")
public class DingtalkProperties {

    /**
     * 应用Key
     */
    private String appKey;

    /**
     * 应用Secret
     */
    private String appSecret;

    /**
     * 回调地址
     */
    private String redirectUrl = "http://localhost:8778/auth/login";

    /**
     * 获取AccessToken的URL
     */
    private String accessTokenUrl = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";

    /**
     * 获取用户信息的URL
     */
    private String userInfoUrl = "https://api.dingtalk.com/v1.0/contact/users/me";
}

