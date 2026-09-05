package com.ykc.hubble.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Role;
import com.ykc.hubble.vo.ApiDegradationVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final GatewayService gatewayService;
    private final MiddlewareMonitorService middlewareMonitorService;

    @Value("${dashscope.apiKey:}")
    private String apiKey;

    @Value("${dashscope.chatModel:qwen-turbo}")
    private String chatModel;

    private static final int MAX_HISTORY_ROUNDS = 5;
    private static final long SESSION_TTL_MS = 30 * 60 * 1000;

    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

    private static class SessionContext {
        final CopyOnWriteArrayList<Message> history = new CopyOnWriteArrayList<>();
        volatile long lastAccessTime = System.currentTimeMillis();

        void touch() {
            lastAccessTime = System.currentTimeMillis();
        }

        void addUser(String content) {
            history.add(Message.builder().role(Role.USER.getValue()).content(content).build());
            trimHistory();
        }

        void addAssistant(String content) {
            history.add(Message.builder().role(Role.ASSISTANT.getValue()).content(content).build());
            trimHistory();
        }

        private void trimHistory() {
            while (history.size() > MAX_HISTORY_ROUNDS * 2) {
                history.remove(0);
            }
        }
    }

    @PostConstruct
    public void init() {
        new Timer("ai-session-cleanup", true).schedule(new TimerTask() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                sessions.entrySet().removeIf(e -> now - e.getValue().lastAccessTime > SESSION_TTL_MS);
            }
        }, SESSION_TTL_MS, SESSION_TTL_MS);
    }

    public void chat(String sessionId, String userMessage, ChunkCallback callback) {
        SessionContext ctx = sessions.computeIfAbsent(sessionId, k -> new SessionContext());
        ctx.touch();

        String systemPrompt = buildSystemPrompt(userMessage);
        ctx.addUser(userMessage);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build());
        messages.addAll(ctx.history);

        try {
            GenerationParam param = GenerationParam.builder()
                    .model(chatModel)
                    .apiKey(apiKey)
                    .messages(messages)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .incrementalOutput(true)
                    .build();

            Generation gen = new Generation();
            StringBuilder fullReply = new StringBuilder();

            gen.streamCall(param, new ResultCallback<>() {
                @Override
                public void onEvent(GenerationResult result) {
                    String delta = extractDelta(result);
                    if (delta != null && !delta.isEmpty()) {
                        fullReply.append(delta);
                        callback.onChunk(delta);
                    }
                }

                @Override
                public void onComplete() {
                    ctx.addAssistant(fullReply.toString());
                    callback.onComplete();
                }

                @Override
                public void onError(Exception e) {
                    log.error("DashScope 流式调用异常: {}", e.getMessage(), e);
                    callback.onError("AI 服务异常: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("DashScope 调用失败: {}", e.getMessage(), e);
            callback.onError("AI 服务调用失败: " + e.getMessage());
        }
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    private String extractDelta(GenerationResult result) {
        if (result == null || result.getOutput() == null) return null;
        if (result.getOutput().getChoices() != null && !result.getOutput().getChoices().isEmpty()) {
            var choice = result.getOutput().getChoices().get(0);
            if (choice.getMessage() != null) {
                return choice.getMessage().getContent();
            }
        }
        return result.getOutput().getText();
    }

    private String buildSystemPrompt(String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Hubble 监控平台的运维 AI 助手，名字叫「Hubble 小助手」。\n");
        sb.append("你的职责是根据提供的实时监控数据，用简洁中文回答运维人员的问题。\n");
        sb.append("规则：\n");
        sb.append("1. 只基于提供的数据回答，不编造数据\n");
        sb.append("2. 数据不足时如实告知\n");
        sb.append("3. 给出可操作的建议\n");
        sb.append("4. 用表格或列表让回答清晰\n\n");

        sb.append("=== 当前监控数据 ===\n");
        sb.append(fetchContextData(userMessage));
        sb.append("\n=== 数据结束 ===\n");

        return sb.toString();
    }

    private String fetchContextData(String userMessage) {
        StringBuilder sb = new StringBuilder();
        String msg = userMessage.toLowerCase();

        boolean matched = false;

        if (containsAny(msg, "劣化", "接口劣化", "rt", "响应时间", "延迟")) {
            sb.append("\n【接口劣化排名】\n");
            sb.append(formatDegradationData(safeCall(() -> gatewayService.degradation("day"))));
            matched = true;
        }

        if (containsAny(msg, "流量", "暴涨", "qps", "请求量")) {
            sb.append("\n【流量暴涨排名】\n");
            sb.append(formatTrafficSurgeData(safeCall(() -> gatewayService.trafficSurge("day"))));
            matched = true;
        }

        if (containsAny(msg, "p60", "p90", "耗时排名", "慢接口")) {
            sb.append("\n【P60 耗时排名】\n");
            sb.append(formatDegradationData(safeCall(() -> gatewayService.p60Ranking("day"))));
            matched = true;
        }

        if (containsAny(msg, "概览", "网关", "总览", "overview")) {
            sb.append("\n【网关概览】\n");
            sb.append(formatOverviewData());
            matched = true;
        }

        if (containsAny(msg, "redis", "缓存")) {
            sb.append("\n【Redis 实例监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.redisInstances())));
            matched = true;
        }

        if (containsAny(msg, "mysql", "数据库", "polardb", "rds")) {
            sb.append("\n【MySQL/PolarDB 实例监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.mysqlInstances())));
            matched = true;
        }

        if (containsAny(msg, "pod", "容器")) {
            sb.append("\n【Pod CPU Top】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.podCpuTop())));
            matched = true;
        }

        if (containsAny(msg, "内存", "memory", "mem")) {
            sb.append("\n【Pod 内存 Top】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.podMemoryTop())));
            matched = true;
        }

        if (containsAny(msg, "rocketmq", "rocket", "消息队列", "mq")) {
            sb.append("\n【RocketMQ 实例监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.rocketmqInstances())));
            matched = true;
        }

        if (containsAny(msg, "kafka", "消息流")) {
            sb.append("\n【Kafka 实例监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.kafkaInstances())));
            matched = true;
        }

        if (containsAny(msg, "lindorm", "宽表")) {
            sb.append("\n【Lindorm 实例监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.lindormInstances())));
            matched = true;
        }

        if (containsAny(msg, "elasticsearch", "es", "搜索", "全文")) {
            sb.append("\n【Elasticsearch 实例监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.elasticsearchInstances())));
            matched = true;
        }

        if (containsAny(msg, "oss", "对象存储", "bucket", "存储桶")) {
            sb.append("\n【OSS Bucket 监控】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.ossBuckets())));
            matched = true;
        }

        if (containsAny(msg, "node", "节点", "机器")) {
            sb.append("\n【Node 节点概览】\n");
            sb.append(formatMiddlewareData(safeCall(() -> middlewareMonitorService.nodeOverview())));
            matched = true;
        }

        if (!matched) {
            sb.append("\n【综合概览】\n");
            sb.append(formatOverviewData());
            List<ApiDegradationVO> degradation = safeCall(() -> gatewayService.degradation("day"));
            if (degradation != null && !degradation.isEmpty()) {
                sb.append("\n【接口劣化 Top5】\n");
                sb.append(formatDegradationData(degradation.subList(0, Math.min(5, degradation.size()))));
            }
            List<ApiDegradationVO> surge = safeCall(() -> gatewayService.trafficSurge("day"));
            if (surge != null && !surge.isEmpty()) {
                sb.append("\n【流量暴涨 Top5】\n");
                sb.append(formatTrafficSurgeData(surge.subList(0, Math.min(5, surge.size()))));
            }
        }

        return sb.toString();
    }

    private boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private <T> T safeCall(java.util.concurrent.Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            log.warn("AI 上下文数据拉取失败: {}", e.getMessage());
            return null;
        }
    }

    private String formatDegradationData(List<ApiDegradationVO> data) {
        if (data == null || data.isEmpty()) return "暂无数据\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            var item = data.get(i);
            sb.append(String.format("%d. %s | RT: %.1f→%.1fms | 劣化: %.1f%% | 请求数: %d\n",
                    i + 1,
                    safe(item.getApiPath()),
                    item.getCurrentAvgTime(),
                    item.getPreviousAvgTime(),
                    item.getDegradationRate(),
                    item.getCurrentCount()));
        }
        return sb.toString();
    }

    private String formatTrafficSurgeData(List<ApiDegradationVO> data) {
        if (data == null || data.isEmpty()) return "暂无数据\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            var item = data.get(i);
            sb.append(String.format("%d. %s | 请求数: %d→%d | 涨幅: %.1f%%\n",
                    i + 1,
                    safe(item.getApiPath()),
                    item.getPreviousCount(),
                    item.getCurrentCount(),
                    item.getDegradationRate()));
        }
        return sb.toString();
    }

    private String formatOverviewData() {
        try {
            var overview = gatewayService.overview("24h");
            if (overview == null) return "暂无概览数据\n";
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("总请求数: %d | 平均 RT: %.1fms | 错误率: %.2f%% | QPS: %.1f\n",
                    overview.getTotalRequests(),
                    overview.getAvgResponseTime(),
                    overview.getErrorRate(),
                    overview.getQps()));
            sb.append(String.format("趋势 — 请求: %.1f%% | RT: %.1f%% | 错误: %.1f%% | QPS: %.1f%%\n",
                    overview.getTotalTrend(),
                    overview.getAvgTrend(),
                    overview.getErrorTrend(),
                    overview.getQpsTrend()));
            return sb.toString();
        } catch (Exception e) {
            return "概览数据获取失败: " + e.getMessage() + "\n";
        }
    }

    private String formatMiddlewareData(Object data) {
        if (data == null) return "暂无数据\n";
        if (data instanceof List<?> list) {
            if (list.isEmpty()) return "暂无数据\n";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(list.size(), 10); i++) {
                sb.append(String.format("%d. %s\n", i + 1, list.get(i)));
            }
            if (list.size() > 10) sb.append(String.format("... 共 %d 条\n", list.size()));
            return sb.toString();
        }
        return data.toString() + "\n";
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    public interface ChunkCallback {
        void onChunk(String text);
        void onComplete();
        void onError(String message);
    }
}
