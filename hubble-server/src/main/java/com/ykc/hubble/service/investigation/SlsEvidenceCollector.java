package com.ykc.hubble.service.investigation;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.entity.LogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SLS 错误日志取证：查告警服务最近 15 分钟 ERROR 日志样本 + 总数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlsEvidenceCollector implements EvidenceCollector {

    private final SlsQueryClient slsQueryClient;

    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final int SAMPLE_LIMIT = 20;
    private static final int MAX_CHARS = 4000;

    @Override
    public String name() {
        return "SLS错误日志";
    }

    @Override
    public Evidence collect(InvestigationContext ctx) {
        String container = containerName(ctx.getServiceName());
        if (container == null) {
            return Evidence.degraded(name(), "无服务名，跳过日志查询");
        }
        try {
            long to = System.currentTimeMillis() / 1000;
            long from = (System.currentTimeMillis() - WINDOW_MS) / 1000;
            String query = "__tag__:_container_name_: " + container + " AND level: ERROR";

            long count = slsQueryClient.countLogstore("all", query, from, to);
            if (count <= 0) {
                return Evidence.ok(name(), "最近15分钟无 ERROR 日志（count=0）");
            }

            List<LogEntry> logs = slsQueryClient.queryLogstore("all", query, from, to, 0, SAMPLE_LIMIT);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("最近15分钟 ERROR 日志共 %d 条，样本 %d 条：\n", count, logs.size()));
            int i = 1;
            for (LogEntry e : logs) {
                String line = String.format("[%s] %s\n%s", e.getTime(), e.getLevel(), truncate(e.getMessage(), 400));
                sb.append(String.format("%d. %s\n", i++, line));
                if (sb.length() > MAX_CHARS) {
                    sb.append("...(超出长度截断)\n");
                    break;
                }
            }
            return Evidence.ok(name(), sb.toString());
        } catch (Exception e) {
            log.warn("SLS 取证失败: service={}, error={}", container, e.getMessage());
            return Evidence.degraded(name(), "SLS 查询失败: " + e.getMessage());
        }
    }

    /** ARMS 应用名（xxx-prod）→ 容器名（去 -prod 后缀）；已无后缀原样返回 */
    static String containerName(String serviceName) {
        if (serviceName == null || serviceName.isBlank() || "-".equals(serviceName)) {
            return null;
        }
        String s = serviceName.trim();
        return s.endsWith("-prod") ? s.substring(0, s.length() - 5) : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
