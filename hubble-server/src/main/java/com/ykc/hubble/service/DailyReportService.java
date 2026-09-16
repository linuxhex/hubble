package com.ykc.hubble.service;

import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.config.DingtalkProperties;
import com.ykc.hubble.entity.DingtalkRobot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private final BizAnalysisService bizAnalysisService;
    private final DingTalkClient dingTalkClient;
    private final DingtalkProperties dingtalkProperties;
    private final DingtalkRobotService dingtalkRobotService;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Scheduled(cron = "0 0 13 * * ?")
    public void sendDailyReport() {
        log.info("开始发送每日业务监控报告");
        try {
            String content = buildReportContent();
            String title = "每日业务监控报告";
            int sentCount = 0;

            // 1. 发送到全局配置的 webhook
            if (StringUtils.hasText(dingtalkProperties.getRobotWebhook())) {
                try {
                    dingTalkClient.sendRobotMarkdown(title, content);
                    sentCount++;
                    log.info("每日报告已发送到全局 webhook");
                } catch (Exception e) {
                    log.warn("发送到全局 webhook 失败: {}", e.getMessage());
                }
            }

            // 2. 发送到告警配置里的所有启用机器人
            List<DingtalkRobot> robots = dingtalkRobotService.listEnabled();
            for (DingtalkRobot robot : robots) {
                if (!StringUtils.hasText(robot.getWebhook())) {
                    continue;
                }
                try {
                    dingTalkClient.sendRobotActionCard(
                            robot.getWebhook(),
                            robot.getSecret(),
                            title,
                            content,
                            "查看详情",
                            dingtalkProperties.getRedirectUrl(),
                            true
                    );
                    sentCount++;
                    log.info("每日报告已发送到机器人: {}", robot.getName());
                } catch (Exception e) {
                    log.warn("发送到机器人 {} 失败: {}", robot.getName(), e.getMessage());
                }
            }

            log.info("每日业务监控报告发送完成，共发送 {} 个机器人", sentCount);
        } catch (Exception e) {
            log.error("发送每日业务监控报告失败: {}", e.getMessage(), e);
        }
    }

    private String buildReportContent() {
        Map<String, Object> overview = bizAnalysisService.dailyOverview();
        String date = String.valueOf(overview.getOrDefault("date", LocalDate.now().minusDays(1).format(DT)));

        long orderCnt = toLong(overview.get("orderCnt"), 0);
        double chargedPower = toDouble(overview.get("chargedPower"), 0);
        long totalGuns = toLong(overview.get("totalGuns"), 0);
        long chargingGuns = toLong(overview.get("chargingGuns"), 0);
        long dau = toLong(overview.get("dau"), 0);
        long adClick = toLong(overview.get("adClick"), 0);

        double utilizationRate = totalGuns > 0 ? Math.round(chargingGuns * 10000.0 / totalGuns) / 100.0 : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("## 每日业务监控报告\n\n");
        sb.append("**日期：").append(date).append("**\n\n");
        sb.append("---\n\n");

        sb.append("### 核心指标\n\n");
        sb.append("| 指标 | 数值 |\n");
        sb.append("|:---|---:|\n");
        sb.append("| 订单数 | ").append(String.format("%,d", orderCnt)).append(" |\n");
        sb.append("| 充电量(kWh) | ").append(String.format("%,.2f", chargedPower)).append(" |\n");
        sb.append("| DAU | ").append(String.format("%,d", dau)).append(" |\n");
        sb.append("| 广告点击 | ").append(String.format("%,d", adClick)).append(" |\n");
        sb.append("| 充电枪利用率 | ").append(String.format("%.1f%%", utilizationRate)).append(" |\n");
        sb.append("| 充电枪数 | ").append(chargingGuns).append("/").append(totalGuns).append(" |\n\n");

        sb.append("---\n\n");
        sb.append("*数据来源：业务监控系统*");

        return sb.toString();
    }

    private static double toDouble(Object val, double defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static long toLong(Object val, long defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number) return ((Number) val).longValue();
        try {
            return Long.parseLong(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
