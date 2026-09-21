package com.ykc.hubble.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ykc.hubble.config.AgentConfig;
import com.ykc.hubble.vo.ApiDegradationVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final GatewayService gatewayService;
    private final MiddlewareMonitorService middlewareMonitorService;
    private final ChatToolService chatToolService;
    private final AgentConfig agentConfig;

    @Value("${llm.base-url:http://10.20.0.239:3020/v1}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:glm-5.2}")
    private String chatModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    private static final int MAX_HISTORY_ROUNDS = 5;
    private static final long SESSION_TTL_MS = 30 * 60 * 1000;

    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();

    /** OpenAI 兼容协议的对话消息 */
    private static class Msg {
        final String role;
        String content;
        String toolCallId;
        List<ToolCall> toolCalls;

        Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class ToolCall {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    private static class SessionContext {
        final CopyOnWriteArrayList<Msg> history = new CopyOnWriteArrayList<>();
        volatile long lastAccessTime = System.currentTimeMillis();

        void touch() {
            lastAccessTime = System.currentTimeMillis();
        }

        void addUser(String content) {
            history.add(new Msg("user", content));
            trimHistory();
        }

        void addAssistant(String content) {
            history.add(new Msg("assistant", content));
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

        List<Msg> messages = new ArrayList<>();
        messages.add(new Msg("system", systemPrompt));
        messages.addAll(ctx.history);

        List<JsonObject> tools = chatToolService.toolSchemas();
        StringBuilder finalText = new StringBuilder();

        try {
            int maxRounds = Math.max(1, agentConfig.getMaxToolRounds());
            for (int round = 0; round < maxRounds; round++) {
                RoundOutcome outcome = streamRound(messages, tools, callback);
                outcome.toolCalls.removeIf(c -> c.name == null || c.name.isEmpty());

                // 无工具调用 → 纯文本回答，结束
                if (outcome.toolCalls.isEmpty()) {
                    finalText = outcome.text;
                    break;
                }

                // 回填 assistant 工具调用消息，执行工具并回填结果，继续下一轮
                Msg assistantMsg = new Msg("assistant", outcome.text.toString());
                assistantMsg.toolCalls = outcome.toolCalls;
                messages.add(assistantMsg);

                for (ToolCall call : outcome.toolCalls) {
                    String args = call.args.toString();
                    log.info("AI 工具调用 round={}: {} args={}", round + 1, call.name, args);
                    try {
                        callback.onToolStatus(chatToolService.statusLabel(call.name, args));
                    } catch (Exception ignored) {
                    }
                    String result = chatToolService.execute(call.name, args);
                    Msg toolMsg = new Msg("tool", truncateForContext(result));
                    toolMsg.toolCallId = call.id;
                    messages.add(toolMsg);
                }
            }

            if (finalText.isEmpty()) {
                String note = "\n（工具调用轮次已达上限，请缩小问题范围后继续）";
                callback.onChunk(note);
                finalText.append(note);
            }

            ctx.addAssistant(finalText.toString());
            callback.onComplete();
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage(), e);
            callback.onError("AI 服务调用失败: " + e.getMessage());
        }
    }

    /** 单轮流式调用结果：增量文本 + 聚合后的工具调用 */
    private static class RoundOutcome {
        final StringBuilder text = new StringBuilder();
        final List<ToolCall> toolCalls = new ArrayList<>();
    }

    private JsonObject msgToJson(Msg m) {
        JsonObject o = new JsonObject();
        o.addProperty("role", m.role);
        if (m.content != null && !m.content.isEmpty()) {
            o.addProperty("content", m.content);
        } else if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
            o.addProperty("content", "");
        }
        if (m.toolCallId != null) {
            o.addProperty("tool_call_id", m.toolCallId);
        }
        if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall c : m.toolCalls) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", c.name);
                fn.addProperty("arguments", c.args.toString());
                JsonObject call = new JsonObject();
                call.addProperty("id", c.id);
                call.addProperty("type", "function");
                call.add("function", fn);
                calls.add(call);
            }
            o.add("tool_calls", calls);
        }
        return o;
    }

    /**
     * 发起一轮流式调用（OpenAI 兼容 SSE）：文本增量实时回调前端；工具调用增量分片按 index 聚合。
     */
    private RoundOutcome streamRound(List<Msg> messages, List<JsonObject> tools, ChunkCallback callback)
            throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", chatModel);
        body.addProperty("stream", true);
        JsonArray msgArr = new JsonArray();
        messages.forEach(m -> msgArr.add(msgToJson(m)));
        body.add("messages", msgArr);
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolArr = new JsonArray();
            tools.forEach(toolArr::add);
            body.add("tools", toolArr);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() != 200 || contentType.contains("application/json")) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("LLM 网关返回 " + response.statusCode() + ": "
                    + (err.length() > 300 ? err.substring(0, 300) : err));
        }

        RoundOutcome outcome = new RoundOutcome();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":") || !line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) break;
                handleChunk(JsonParser.parseString(payload).getAsJsonObject(), outcome, callback);
            }
        }
        return outcome;
    }

    /** 解析 SSE chunk：content 增量实时回调；tool_calls 分片按 index 聚合 */
    private void handleChunk(JsonObject chunk, RoundOutcome outcome, ChunkCallback callback) {
        if (!chunk.has("choices") || !chunk.get("choices").isJsonArray()) return;
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices.isEmpty()) return;
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) return;
        JsonObject delta = choice.getAsJsonObject("delta");

        if (delta.has("content") && !delta.get("content").isJsonNull()) {
            String text = delta.get("content").getAsString();
            if (!text.isEmpty()) {
                outcome.text.append(text);
                callback.onChunk(text);
            }
        }
        if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
            for (var el : delta.getAsJsonArray("tool_calls")) {
                JsonObject tc = el.getAsJsonObject();
                int idx = tc.has("index") && !tc.get("index").isJsonNull()
                        ? tc.get("index").getAsInt() : outcome.toolCalls.size();
                while (outcome.toolCalls.size() <= idx) {
                    outcome.toolCalls.add(new ToolCall());
                }
                ToolCall target = outcome.toolCalls.get(idx);
                if (tc.has("id") && !tc.get("id").isJsonNull() && !tc.get("id").getAsString().isEmpty()) {
                    target.id = tc.get("id").getAsString();
                }
                if (tc.has("function") && tc.get("function").isJsonObject()) {
                    JsonObject fn = tc.getAsJsonObject("function");
                    if (fn.has("name") && !fn.get("name").isJsonNull() && !fn.get("name").getAsString().isEmpty()
                            && (target.name == null || target.name.isEmpty())) {
                        target.name = fn.get("name").getAsString();
                    }
                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                        target.args.append(fn.get("arguments").getAsString());
                    }
                }
            }
        }
    }

    /** 工具结果注入上下文的最大字符数 */
    private static final int TOOL_RESULT_MAX_CHARS = 12000;
    /** 超长时保留的尾部字符数（日志类结果最新内容通常在尾部） */
    private static final int TOOL_RESULT_TAIL_CHARS = 3200;

    /**
     * 工具结果过长时保留头部+尾部：只留头部会让 AI 看不到最新的日志/数据得出片面结论。
     * 中间省略并在提示中说明，引导模型缩小查询范围。
     */
    private String truncateForContext(String s) {
        if (s == null) return "";
        if (s.length() <= TOOL_RESULT_MAX_CHARS) return s;
        int headChars = TOOL_RESULT_MAX_CHARS - TOOL_RESULT_TAIL_CHARS;
        String note = "\n...（结果过长已截断：共 " + s.length() + " 字符，保留前 " + headChars
                + " 字符与末尾 " + TOOL_RESULT_TAIL_CHARS + " 字符，中间省略；如需完整信息请缩小查询范围或减少返回条数）...\n";
        return s.substring(0, headChars) + note + s.substring(s.length() - TOOL_RESULT_TAIL_CHARS);
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    private String buildSystemPrompt(String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Hubble 监控平台的运维 AI 助手，名字叫「Hubble 小助手」。\n");
        sb.append("你的职责是回答运维人员的问题：既能基于预置监控数据快速回答，也能调用工具做深度排查（查日志、查链路、看代码定位根因）。\n");
        sb.append("规则：\n");
        sb.append("1. 基于数据和工具结果回答，不编造数据；查询无结果就如实说\n");
        sb.append("2. 给出可操作的建议，用表格或列表让回答清晰\n");
        sb.append("3. 深度排查遵循「日志先行、代码验证」：先搜日志拿证据（单号/traceId 优先，关联 ID 零噪音），再用 search_code/read_code 验证根因\n");
        sb.append("4. 根因两层都要给：技术根因（代码/超时/依赖/SQL，尽量落到文件:行号）+ 业务根因（什么业务操作/配置/数据状态触发）\n");
        sb.append("5. 结论必须给「根因 + 证据 + 怎么验证」，证据不足就说明还差什么，不输出\"可能是\"当最终结论\n\n");

        sb.append("=== 排查工具使用要点 ===\n");
        sb.append("- SLS 日志：业务服务日志在 all 库，用服务名（spring.name，如 orderserver）作关键字过滤；日志的 trace 字段即 traceId，可喂给 arms_trace_detail\n");
        sb.append("- ARMS 链路：先 list_arms_apps 拿 pid，再 arms_api_metrics 看接口性能，arms_trace_detail 看单链路 span 树（自动标记 Redis/SQL 慢调用）\n");
        sb.append("- 每个工具可以多次调用、多轮迭代，直到根因清晰\n");
        sb.append("\n=== 当前监控数据 ===\n");
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

        /** 工具执行状态（前端展示"正在查询 xxx"），默认空实现保持兼容 */
        default void onToolStatus(String status) {
        }

        void onComplete();
        void onError(String message);
    }
}
