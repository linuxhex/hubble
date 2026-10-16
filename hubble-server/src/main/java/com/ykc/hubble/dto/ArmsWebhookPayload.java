package com.ykc.hubble.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * ARMS 告警 Webhook 回调 payload（AlertManager 标准格式）
 */
@Data
public class ArmsWebhookPayload {

    private String status;
    private String receiver;
    private String externalURL;
    private List<Alert> alerts;
    private Map<String, String> commonLabels;
    private Map<String, String> commonAnnotations;
    private Map<String, String> groupLabels;

    @Data
    public static class Alert {
        private String status;
        private String fingerprint;
        private String startsAt;
        private String endsAt;
        private Map<String, String> labels;
        private Map<String, String> annotations;
    }
}
