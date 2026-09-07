package com.ykc.hubble;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务链路查询平台启动类
 *
 * @author Cloud Eyes Team
 */
@SpringBootApplication
@MapperScan("com.ykc.hubble.mapper")
public class HubbleApplication {

    public static void main(String[] args) {
        SpringApplication.run(HubbleApplication.class, args);
    }
}

