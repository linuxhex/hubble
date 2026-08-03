package com.ykc.hubble.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用定时调度，供大盘快照采集任务使用
 *
 * @author Cloud Eyes Team
 */
@Configuration
@EnableScheduling
public class MonitorSchedulingConfig {
}
