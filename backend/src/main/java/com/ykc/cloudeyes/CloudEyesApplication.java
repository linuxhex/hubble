package com.ykc.cloudeyes;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 业务链路查询平台启动类
 *
 * @author Cloud Eyes Team
 */
@SpringBootApplication
@MapperScan("com.ykc.cloudeyes.mapper")
public class CloudEyesApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudEyesApplication.class, args);
    }
}

