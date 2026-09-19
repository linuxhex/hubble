package com.ykc.hubble.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 助手 Agent 配置（工具调用/排查能力）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {

    /**
     * AI 排查可读的代码工作区根目录（如 /Users/xxx/Work/code-projects），为空则禁用代码检索工具
     */
    private String codeWorkspace = "";

    /**
     * 单次对话允许的最大工具调用轮次
     */
    private int maxToolRounds = 8;

    /**
     * cwork-config Nacos 查询脚本路径，为空则禁用配置查询工具
     */
    private String nacosScriptPath = System.getProperty("user.home") + "/.qoder/skills/cwork-config/scripts/nacos_query.sh";
}
