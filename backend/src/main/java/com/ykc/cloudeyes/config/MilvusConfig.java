package com.ykc.cloudeyes.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus配置类
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
public class MilvusConfig {

    /**
     * Milvus服务地址
     */
    private String host = "localhost";

    /**
     * Milvus服务端口
     */
    private Integer port = 19530;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 数据库名称
     */
    private String database;

    /**
     * 向量维度（默认1024，支持text-embedding-v3模型）
     */
    private Integer vectorDimension = 1024;

    /**
     * 创建Milvus客户端
     */
    @Bean
    public MilvusServiceClient milvusClient() {
        try {
            ConnectParam.Builder builder = ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .withDatabaseName(database);

            // 如果配置了用户名和密码，则添加认证信息
            if (username != null && !username.isEmpty()) {
                builder.withAuthorization(username, password);
            }

            log.info("Milvus连接成功: {}:{}, database: {}", host, port, database);
            return new MilvusServiceClient(builder.build());
        } catch (Exception e) {
            log.error("Milvus连接失败: {}:{}, database: {}", host, port, database, e);
            throw new RuntimeException("Milvus连接失败", e);
        }
    }
}

