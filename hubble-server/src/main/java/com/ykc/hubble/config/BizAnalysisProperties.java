package com.ykc.hubble.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "biz-analysis")
public class BizAnalysisProperties {

    private List<String> allowedUsers = List.of("lianzi");
    private String queryServerUrl = "http://query-server-internal:8080";
    private String queryUser = "";
    private String queryPass = "";
}
