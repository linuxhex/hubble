package com.ykc.hubble.util;

import com.aliyun.openservices.log.Client;
import com.ykc.hubble.config.SlsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SLS客户端工厂
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlsClientFactory {

    private final SlsConfig slsConfig;
    private volatile Client client;

    /**
     * 获取SLS客户端（单例模式）
     *
     * @return SLS客户端
     */
    public Client getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = createClient();
                }
            }
        }
        return client;
    }

    /**
     * 创建SLS客户端
     */
    private Client createClient() {
        try {
            return new Client(
                    slsConfig.getEndpoint(),
                    slsConfig.getAccessKeyId(),
                    slsConfig.getAccessKeySecret()
            );
        } catch (Exception e) {
            log.error("创建SLS客户端失败", e);
            throw new RuntimeException("SLS客户端初始化失败", e);
        }
    }
}
