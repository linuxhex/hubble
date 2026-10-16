package com.ykc.hubble.service;

import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.dto.ArmsWebhookPayload;
import com.ykc.hubble.entity.DingtalkRobot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ARMS 告警 Webhook 接收与转发服务
 * 接收阿里云 ARMS 通过 AlertManager 格式推送的告警，解析后转发到钉钉群
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArmsWebhookService {

    private final DingTalkClient dingTalkClient;
    private final AlertPushService alertPushService;
    private final DingtalkRobotService dingtalkRobotService;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 处理 ARMS Webhook 回调
     */
    public void handleWebhook(ArmsWebhookPayload payload) {
        if (payload == null || payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            log.warn("ARMS Webhook 收到空告警");
            return;
        }

        String overallStatus = payload.getStatus();
        log.info("ARMS Webhook 收到告警: status={}, receiver={}, alertCount={}",
                overallStatus, payload.getReceiver(), payload.getAlerts().size());

        for (ArmsWebhookPayload.Alert alert : payload.getAlerts()) {
            processAlert(alert, overallStatus, payload.getExternalURL());
        }
    }

    private void processAlert(ArmsWebhookPayload.Alert alert, String overallStatus, String externalURL) {
        Map<String, String> labels = alert.getLabels() != null ? alert.getLabels() : Map.of();
        Map<String, String> annotations = alert.getAnnotations() != null ? alert.getAnnotations() : Map.of();

        String alertName = labels.getOrDefault("alertname", "未知告警");
        String severity = labels.getOrDefault("_aliyun_arms_alert_level",
                labels.getOrDefault("severity", "WARNING"));
        String alertStatus = alert.getStatus() != null ? alert.getStatus() : overallStatus;
        String fingerprint = alert.getFingerprint();
        String startsAt = formatTime(alert.getStartsAt());
        String endsAt = formatTime(alert.getEndsAt());

        String alertValue = annotations.getOrDefault("_aliyun_arms_alert_now_value",
                annotations.getOrDefault("value", "-"));
        String pastValue = annotations.getOrDefault("_aliyun_arms_alert_past_value", "-");
        String message = annotations.getOrDefault("message", annotations.getOrDefault("_aliyun_arms_alert_message", ""));

        String regionId = labels.getOrDefault("_aliyun_arms_region_id", "-");
        String productType = labels.getOrDefault("_aliyun_arms_product_type", "-");
        String alertRuleId = labels.getOrDefault("_aliyun_arms_alert_rule_id", "-");
        String clusterName = labels.getOrDefault("clustername", "-");
        String namespace = labels.getOrDefault("namespace", "-");
        String instance = labels.getOrDefault("instance", "-");

        boolean isResolved = "resolved".equalsIgnoreCase(alertStatus);
        String icon = isResolved ? "🟢" : ("ERROR".equalsIgnoreCase(severity) ? "🔴" : "🟡");
        String statusText = isResolved ? "已恢复" : "告警触发";

        StringBuilder md = new StringBuilder();
        md.append(String.format("### %s **%s**\n\n", icon, statusText));
        md.append(String.format("> 告警名称：**%s**\n\n", alertName));
        md.append(String.format("> 告警级别：**%s**\n\n", severity));
        md.append(String.format("> 产品类型：%s\n\n", productType));
        md.append(String.format("> 地域：%s\n\n", regionId));

        if (!"-".equals(clusterName)) {
            md.append(String.format("> 集群：%s\n\n", clusterName));
        }
        if (!"-".equals(namespace)) {
            md.append(String.format("> 命名空间：%s\n\n", namespace));
        }
        if (!"-".equals(instance)) {
            md.append(String.format("> 实例：%s\n\n", instance));
        }

        md.append("\n---\n\n");
        md.append("### 告警详情\n\n");
        md.append(String.format("> 当前值：**%s**\n\n", alertValue));
        if (!"-".equals(pastValue)) {
            md.append(String.format("> 历史值：%s\n\n", pastValue));
        }
        if (message != null && !message.isBlank()) {
            md.append(String.format("> 描述：%s\n\n", cleanMessage(message)));
        }
        md.append(String.format("> 触发时间：%s\n\n", startsAt));
        if (isResolved && endsAt != null && !endsAt.isBlank()) {
            md.append(String.format("> 恢复时间：%s\n\n", endsAt));
        }
        md.append(String.format("> Fingerprint：%s\n\n", fingerprint));

        String title = isResolved ? "ARMS 告警恢复" : "ARMS 告警通知";
        String dingContent = md.toString();

        pushSse(alert, labels, annotations, alertStatus, severity);
        sendDingTalk(title, dingContent, externalURL, isResolved);

        log.info("ARMS 告警处理完成: name={}, status={}, severity={}, fingerprint={}",
                alertName, alertStatus, severity, fingerprint);
    }

    private void pushSse(ArmsWebhookPayload.Alert alert, Map<String, String> labels,
                         Map<String, String> annotations, String status, String severity) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "arms_alert");
            payload.put("alertName", labels.getOrDefault("alertname", "未知告警"));
            payload.put("status", status);
            payload.put("severity", severity);
            payload.put("fingerprint", alert.getFingerprint());
            payload.put("time", Instant.now().toEpochMilli());
            alertPushService.pushAlert(payload);
        } catch (Exception e) {
            log.warn("ARMS 告警 SSE 推送失败: {}", e.getMessage());
        }
    }

    private void sendDingTalk(String title, String content, String externalURL, boolean isResolved) {
        String btnText = isResolved ? "查看告警详情" : "查看详情";

        try {
            List<DingtalkRobot> robots = dingtalkRobotService.listEnabled();
            if (robots.isEmpty()) {
                dingTalkClient.sendRobotActionCard(title, content, btnText, externalURL, true);
                return;
            }
            for (DingtalkRobot robot : robots) {
                try {
                    dingTalkClient.sendRobotActionCard(
                            robot.getWebhook(), robot.getSecret(),
                            title, content, btnText, externalURL, true);
                } catch (Exception e) {
                    log.warn("ARMS 告警钉钉推送失败 (robot={}): {}", robot.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("ARMS 告警获取机器人列表失败，回退全局 Webhook: {}", e.getMessage());
            try {
                dingTalkClient.sendRobotActionCard(title, content, btnText, externalURL, true);
            } catch (Exception ex) {
                log.error("ARMS 告警钉钉推送最终失败: {}", ex.getMessage());
            }
        }
    }

    private String formatTime(String isoTime) {
        if (isoTime == null || isoTime.isBlank() || isoTime.startsWith("0001")) {
            return "-";
        }
        try {
            Instant instant = Instant.parse(isoTime);
            return TIME_FMT.format(instant);
        } catch (Exception e) {
            return isoTime;
        }
    }

    private String cleanMessage(String message) {
        if (message == null) return "";
        return message.replaceAll("\\{\\{.*?\\}\\}", "")
                .replaceAll("\\$labels\\.\\w+", "")
                .replaceAll("\\$value", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
