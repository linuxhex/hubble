package com.ykc.hubble.client;

import cn.hutool.json.JSONUtil;
import com.ykc.hubble.config.DingtalkProperties;
import com.ykc.hubble.dto.DingTalkUserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    /**
     * 发送 Markdown 消息到钉钉群机器人
     *
     * @param title   消息标题
     * @param content Markdown 正文
     */
    public void sendRobotMarkdown(String title, String content) {
        String webhook = dingtalkProperties.getRobotWebhook();
        if (!StringUtils.hasText(webhook)) {
            log.debug("钉钉机器人 Webhook 未配置，跳过告警通知");
            return;
        }
        try {
            String signedUrl = buildSignedWebhookUrl(webhook, dingtalkProperties.getRobotSecret());

            Map<String, Object> payload = new HashMap<>();
            payload.put("msgtype", "markdown");
            Map<String, String> markdown = new HashMap<>();
            markdown.put("title", title);
            markdown.put("text", content);
            payload.put("markdown", markdown);

            ResponseEntity<String> response = restTemplate.postForEntity(signedUrl, payload, String.class);
            log.info("钉钉机器人通知发送成功: {}", response.getBody());
        } catch (Exception e) {
            log.error("钉钉机器人通知发送失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送 ActionCard 消息到钉钉群机器人（支持图片 + 按钮）
     * 使用全局配置的 Webhook + Secret。
     *
     * @param title      卡片标题
     * @param markdown   Markdown 正文（可含图片）
     * @param btnTitle   按钮文案
     * @param btnUrl     按钮链接
     * @param singleTile true=整体跳转，false=独立跳转
     */
    public void sendRobotActionCard(String title, String markdown, String btnTitle, String btnUrl, boolean singleTile) {
        String webhook = dingtalkProperties.getRobotWebhook();
        if (!StringUtils.hasText(webhook)) {
            log.debug("钉钉机器人 Webhook 未配置，跳过告警通知");
            return;
        }
        sendRobotActionCard(webhook, dingtalkProperties.getRobotSecret(), title, markdown, btnTitle, btnUrl, singleTile);
    }

    /**
     * 发送 ActionCard 消息到指定钉钉机器人（支持多机器人多群）。
     *
     * @param webhook    机器人 Webhook 地址
     * @param secret     加签密钥（选填）
     * @param title      卡片标题
     * @param markdown   Markdown 正文
     * @param btnTitle   按钮文案
     * @param btnUrl     按钮链接
     * @param singleTile true=整体跳转，false=独立跳转
     */
    public void sendRobotActionCard(String webhook, String secret, String title, String markdown, String btnTitle, String btnUrl, boolean singleTile) {
        if (!StringUtils.hasText(webhook)) {
            log.debug("钉钉机器人 Webhook 为空，跳过告警通知");
            return;
        }
        try {
            String signedUrl = buildSignedWebhookUrl(webhook, secret);

            Map<String, Object> payload = new HashMap<>();
            if (singleTile) {
                payload.put("msgtype", "actionCard");
                Map<String, Object> card = new HashMap<>();
                card.put("title", title);
                card.put("text", markdown);
                card.put("singleTitle", btnTitle);
                card.put("singleURL", btnUrl);
                card.put("btnOrientation", "0");
                payload.put("actionCard", card);
            } else {
                payload.put("msgtype", "actionCard");
                Map<String, Object> card = new HashMap<>();
                card.put("title", title);
                card.put("text", markdown);
                card.put("btnOrientation", "0");
                java.util.List<Map<String, String>> btns = new java.util.ArrayList<>();
                Map<String, String> btn = new HashMap<>();
                btn.put("title", btnTitle);
                btn.put("actionURL", btnUrl);
                btns.add(btn);
                card.put("btns", btns);
                payload.put("actionCard", card);
            }

            ResponseEntity<String> response = restTemplate.postForEntity(signedUrl, payload, String.class);
            log.info("钉钉 ActionCard 通知发送成功: {}", response.getBody());
        } catch (Exception e) {
            log.error("钉钉 ActionCard 通知发送失败: {}", e.getMessage(), e);
        }
    }

    private String buildSignedWebhookUrl(String webhookUrl, String secret) {
        if (!StringUtils.hasText(secret)) {
            return webhookUrl;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);

            String separator = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            log.error("钉钉签名生成失败: {}", e.getMessage());
            return webhookUrl;
        }
    }
}

