package com.ykc.hubble.client;

import cn.hutool.json.JSONUtil;
import com.ykc.hubble.config.DingtalkProperties;
import com.ykc.hubble.dto.DingTalkUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉客户端
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingTalkClient {

    private final RestTemplate restTemplate;
    private final DingtalkProperties dingtalkProperties;

    /**
     * 根据钉钉code获取用户accessToken
     *
     * @param appKey 应用Key
     * @param appSecret 应用Secret
     * @param code 授权码
     * @return accessToken
     */
    public String getUserAccessToken(String appKey, String appSecret, String code) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("clientId", appKey);
            params.put("clientSecret", appSecret);
            params.put("grantType", "authorization_code");
            params.put("code", code);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    dingtalkProperties.getAccessTokenUrl(),
                    request,
                    String.class
            );
            String body = response.getBody();
            log.info("getUserAccessToken response: {}", body);

            if (body != null && !body.isEmpty()) {
                cn.hutool.json.JSONObject bodyJson = JSONUtil.parseObj(body);
                return bodyJson.getStr("accessToken");
            }
        } catch (Exception e) {
            log.error("get user access token error: {}", e.getMessage(), e);
        }

        return null;
    }

    /**
     * 根据accessToken获取用户信息
     *
     * @param userAccessToken 用户访问令牌
     * @return 用户信息
     */
    public DingTalkUserDTO getUserInfoByAccessToken(String userAccessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Host", "api.dingtalk.com");
            headers.set("x-acs-dingtalk-access-token", userAccessToken);
            HttpEntity<String> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    dingtalkProperties.getUserInfoUrl(),
                    HttpMethod.GET,
                    request,
                    String.class
            );
            String body = response.getBody();
            log.info("getUserInfoByAccessToken response: {}", body);

            if (body != null && !body.isEmpty()) {
                return JSONUtil.toBean(body, DingTalkUserDTO.class);
            }
        } catch (Exception e) {
            log.error("Failed to get user info with access token: {}", userAccessToken, e);
        }
        return null;
    }
}

