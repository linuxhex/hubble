package com.ykc.hubble.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.entity.DingtalkRobot;
import com.ykc.hubble.entity.IncidentMemory;
import com.ykc.hubble.service.investigation.Evidence;
import com.ykc.hubble.service.investigation.EvidenceCollector;
import com.ykc.hubble.service.investigation.InvestigationContext;
import com.ykc.hubble.service.investigation.LogSanitizer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 告警驱动的 AI 事故调查编排（借鉴 OpenSRE 调查流水线，Java 固定流水线实现）：
 * 去重/并发闸门 → 四路并行取证（各 20s 超时降级）→ 脱敏 → LLM 综合分析（90s 超时，JSON 重试 1 次）
 * → 落库 → 钉钉第二条报告 + SSE。
 *
 * 可靠性底线：调查是增强不是依赖——submit 之后的一切失败只影响调查记录本身，绝不反向影响告警推送。
 */
@Slf4j
@Service
public class AlertInvestigationService {

    private final IncidentMemoryService incidentMemoryService;
    private final AlertPushService alertPushService;
    private final DingTalkClient dingTalkClient;
    private final DingtalkRobotService dingtalkRobotService;
    private final List<EvidenceCollector> collectors;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("alert-investigation-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    /** 全局并发上限：同时最多 2 个调查，防告警风暴打爆 LLM */
    private final Semaphore permits = new Semaphore(2);

    private static final Duration COLLECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(90);
    private static final int EVIDENCE_MAX_CHARS = 4000;
    private static final int DEDUP_WINDOW_MINUTES = 30;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    @Value("${llm.base-url:http://10.20.0.239:3020/v1}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:glm-5.2}")
    private String chatModel;

    @Value("${monitor.dashboard-url:http://172.16.149.95:18081}")
    private String dashboardUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    public AlertInvestigationService(IncidentMemoryService incidentMemoryService,
                                     AlertPushService alertPushService,
                                     DingTalkClient dingTalkClient,
                                     DingtalkRobotService dingtalkRobotService,
                                     List<EvidenceCollector> collectors) {
        this.incidentMemoryService = incidentMemoryService;
        this.alertPushService = alertPushService;
        this.dingTalkClient = dingTalkClient;
        this.dingtalkRobotService = dingtalkRobotService;
        this.collectors = collectors;
    }

    @PostConstruct
    public void init() {
        // 启动恢复：重启导致悬挂的调查置 FAILED（可页面重试）
        incidentMemoryService.failStaleInvestigations(10);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 提交调查（告警源钩子调用；调用方已做级别过滤）。
     * 先发原告警再调用本方法——本方法内任何异常都被吞掉，只记日志。
     */
    public void submit(InvestigationContext ctx) {
        try {
            String dedupKey = buildDedupKey(ctx);
            IncidentMemory recent = incidentMemoryService.findRecentByDedupKey(dedupKey, DEDUP_WINDOW_MINUTES);
            if (recent != null) {
                log.info("事故调查去重跳过: dedupKey={}, 已有记录 id={} status={}", dedupKey, recent.getId(), recent.getStatus());
                return;
            }
            if (!permits.tryAcquire()) {
                log.warn("事故调查并发已满（2），丢弃本次调查: {} / {}", ctx.getAlertSource(), ctx.getAlertName());
                return;
            }
            // 注意：信号量已获取，后续任何异常都必须释放，否则并发槽永久泄漏。
            boolean released = false;
            try {
                IncidentMemory record = new IncidentMemory();
                record.setAlertSource(ctx.getAlertSource());
                record.setAlertType(ctx.getAlertType());
                record.setAlertName(ctx.getAlertName());
                record.setServiceName(ctx.getServiceName());
                record.setFingerprint(ctx.getFingerprint());
                record.setDedupKey(dedupKey);
                record.setSeverity(ctx.getSeverity());
                record.setAlertValue(ctx.getAlertValue());
                record.setStatus("INVESTIGATING");
                record.setResolved(0);
                record.setRetryCount(0);
                incidentMemoryService.save(record);
                final long recordId = record.getId();
                executor.submit(() -> {
                    try {
                        run(recordId, ctx);
                    } finally {
                        permits.release();
                    }
                });
                released = true; // 已转交异步任务，由其 finally 释放
                log.info("事故调查已提交: id={} source={} name={} service={}",
                        recordId, ctx.getAlertSource(), ctx.getAlertName(), ctx.getServiceName());
            } finally {
                if (!released) {
                    permits.release(); // submit 前任何异常都补偿释放
                }
            }
        } catch (Exception e) {
            log.error("事故调查提交失败（不影响告警推送）: {}", e.getMessage(), e);
        }
    }

    /** 重试失败调查（页面触发；复用同一闸门与去重） */
    public boolean retry(Long id) {
        IncidentMemory record = incidentMemoryService.getById(id);
        if (record == null) {
            return false;
        }
        if (!"FAILED".equals(record.getStatus())) {
            log.warn("事故调查重试拒绝: id={} status={}（仅 FAILED 可重试）", id, record.getStatus());
            return false;
        }
        InvestigationContext ctx = InvestigationContext.builder()
                .alertSource(record.getAlertSource())
                .alertType(record.getAlertType())
                .alertName(record.getAlertName())
                .serviceName(record.getServiceName())
                .severity(record.getSeverity())
                .alertValue(record.getAlertValue())
                .fingerprint(record.getFingerprint())
                .alertTimeMs(System.currentTimeMillis())
                .build();
        record.setStatus("INVESTIGATING");
        record.setErrorMessage(null);
        record.setRetryCount((record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1);
        incidentMemoryService.update(record);
        executor.submit(() -> run(id, ctx));
        log.info("事故调查重试: id={} retryCount={}", id, record.getRetryCount());
        return true;
    }

    /** 流水线主体：四路并行取证 → 脱敏 → LLM → 落库 → 推送 */
    private void run(Long recordId, InvestigationContext ctx) {
        try {
            // 1. 四路取证并行，各 20s 超时降级
            List<CompletableFuture<Evidence>> futures = collectors.stream()
                    .map(c -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return c.collect(ctx);
                        } catch (Exception e) {
                            return Evidence.degraded(c.name(), "采集器异常: " + e.getMessage());
                        }
                    }, executor).orTimeout(COLLECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .exceptionally(ex -> Evidence.degraded(c.name(), "取证超时或异常: " + ex.getMessage())))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<Evidence> evidences = futures.stream().map(CompletableFuture::join).toList();

            // 2. 脱敏 + 限长
            for (Evidence ev : evidences) {
                if (ev.getContent() != null && ev.getContent().length() > EVIDENCE_MAX_CHARS) {
                    ev.setContent(ev.getContent().substring(0, EVIDENCE_MAX_CHARS) + "...(截断)");
                }
                ev.setContent(LogSanitizer.sanitize(ev.getContent()));
                if (ev.getNote() != null) {
                    ev.setNote(LogSanitizer.sanitize(ev.getNote()));
                }
            }

            // 3. LLM 综合分析
            com.google.gson.JsonObject reportJson = analyze(ctx, evidences);

            // 4. 落库
            IncidentMemory record = incidentMemoryService.getById(recordId);
            if (record == null) {
                log.warn("事故调查记录不存在，跳过落库: id={}", recordId);
                return;
            }
            record.setStatus("DONE");
            record.setRootCause(extractField(reportJson, "root_cause"));
            record.setAnalysisSummary(extractField(reportJson, "analysis_summary"));
            record.setRecommendation(extractField(reportJson, "recommendation"));
            // confidence 可能是数组（LLM 幻觉），防御性处理
            var confEl = reportJson.get("confidence");
            record.setConfidence(confEl == null || confEl.isJsonNull() || !confEl.isJsonPrimitive() ? null
                    : confEl.getAsBigDecimal());
            record.setEvidenceJson(gson.toJson(evidences.stream()
                    .map(e -> Map.of("name", e.getName(), "content", e.getContent(),
                            "degraded", e.isDegraded(), "note", e.getNote() == null ? "" : e.getNote()))
                    .toList()));
            record.setErrorMessage(null);
            incidentMemoryService.update(record);

            // 5. 推钉钉第二条 + SSE
            pushReport(record, ctx);
            pushSse(record);
            log.info("事故调查完成: id={} confidence={}", recordId, record.getConfidence());
        } catch (Exception e) {
            log.error("事故调查失败: id={}, error={}", recordId, e.getMessage(), e);
            markFailed(recordId, e.getMessage());
        }
    }

    /** LLM 单次调用，要求 JSON 输出；解析失败重试 1 次 */
    private JsonObject analyze(InvestigationContext ctx, List<Evidence> evidences) throws Exception {
        String prompt = buildPrompt(ctx, evidences);
        String content = callLlm(prompt + "\n\n注意：只输出 JSON 本体，不要 markdown 代码块包裹。");
        JsonObject parsed = tryParse(content);
        if (parsed == null) {
            log.warn("事故调查 LLM 输出非 JSON，重试一次: {}", truncate(content, 200));
            content = callLlm(prompt + "\n\n上次输出不是合法 JSON。严格只输出 JSON 对象，"
                    + "字段：root_cause(string), analysis_summary(string), recommendation(string), confidence(number 0-1)。"
                    + "不要任何其他文字。\n上次输出片段：" + truncate(content, 300));
            parsed = tryParse(content);
        }
        if (parsed == null) {
            throw new IllegalStateException("LLM 输出无法解析为 JSON: " + truncate(content, 200));
        }
        return parsed;
    }

    private String buildPrompt(InvestigationContext ctx, List<Evidence> evidences) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深 SRE。以下是一条生产告警及自动取证结果，请给出根因分析。\n\n");
        sb.append("## 告警信息\n");
        sb.append(String.format("- 来源：%s\n- 类型：%s\n- 名称：%s%n", ctx.getAlertSource(), ctx.getAlertType(), ctx.getAlertName()));
        sb.append(String.format("- 服务/实例：%s%n- 级别：%s%n- 当前值：%s%n", ctx.getServiceName(), ctx.getSeverity(), ctx.getAlertValue()));
        if (ctx.getMessage() != null && !ctx.getMessage().isBlank()) {
            sb.append(String.format("- 描述：%s%n", ctx.getMessage()));
        }
        sb.append(String.format("- 触发时间：%s%n%n", ctx.getAlertTimeMs() == null ? "-" : TIME_FMT.format(java.time.Instant.ofEpochMilli(ctx.getAlertTimeMs()))));
        sb.append("## 取证证据（degraded 表示该路取证失败，不代表无问题）\n");
        for (Evidence ev : evidences) {
            sb.append(String.format("### %s%s%n%s%n%n", ev.getName(),
                    ev.isDegraded() ? "【降级: " + ev.getNote() + "】" : "",
                    ev.isDegraded() ? "" : ev.getContent()));
        }
        sb.append("## 要求\n");
        sb.append("1. 基于证据推理，不要臆测；证据不足时明确说明缺什么证据并给出获取建议\n");
        sb.append("2. root_cause：一句话根因结论（证据不足时写「疑似方向+待验证点」）\n");
        sb.append("3. analysis_summary：分析推理过程，引用具体证据\n");
        sb.append("4. recommendation：按优先级列出处置建议（最多 3 条）\n");
        sb.append("5. confidence：0-1 置信度，证据越充分越高\n");
        sb.append("只输出 JSON 对象：{\"root_cause\":..,\"analysis_summary\":..,\"recommendation\":..,\"confidence\":..}");
        return sb.toString();
    }

    /** OpenAI 兼容非流式调用（与 AiChatService 同一套 llm.* 配置） */
    private String callLlm(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", chatModel);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", prompt);
        messages.add(msg);
        body.add("messages", messages);
        // 调查是后台任务，不需要长思考输出，控制 token
        body.addProperty("max_tokens", 1500);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(LLM_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("LLM HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
        }
        JsonObject respJson = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonObject message = respJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message");
        return extractMessageContent(message);
    }

    /** 兼容 content 为 string 或多段 array（部分模型如 glm-5.2 会返回数组） */
    /** LLM 返回字段可能是 string 或 array（幻觉），统一提取为字符串 */
    private String extractField(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        var el = json.get(key);
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var item : el.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    sb.append(item.isJsonPrimitive() ? item.getAsString() : item.toString()).append("\n");
                }
            }
            return sb.toString().trim();
        }
        return el.toString();
    }

    private String extractMessageContent(JsonObject message) {
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }
        var content = message.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : content.getAsJsonArray()) {
                if (el == null || el.isJsonNull()) continue;
                if (el.isJsonPrimitive()) {
                    sb.append(el.getAsString());
                } else if (el.isJsonObject()) {
                    JsonObject part = el.getAsJsonObject();
                    // OpenAI 多模态段：{type:"text", text:"..."}
                    if (part.has("text") && !part.get("text").isJsonNull() && part.get("text").isJsonPrimitive()) {
                        sb.append(part.get("text").getAsString());
                    } else if (part.has("content") && !part.get("content").isJsonNull() && part.get("content").isJsonPrimitive()) {
                        sb.append(part.get("content").getAsString());
                    }
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private JsonObject tryParse(String content) {
        try {
            String s = content.trim();
            if (s.startsWith("```")) {
                s = s.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            return o.has("root_cause") ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void markFailed(Long recordId, String reason) {
        try {
            IncidentMemory record = incidentMemoryService.getById(recordId);
            if (record != null) {
                record.setStatus("FAILED");
                record.setErrorMessage(truncate(reason, 480));
                incidentMemoryService.update(record);
            }
        } catch (Exception e) {
            log.error("事故调查失败状态落库失败: id={}, {}", recordId, e.getMessage());
        }
    }

    /** 钉钉第二条报告（复用多 robot 逻辑） */
    private void pushReport(IncidentMemory record, InvestigationContext ctx) {
        StringBuilder md = new StringBuilder();
        md.append("### 🤖 **AI 事故调查报告**\n\n");
        md.append(String.format("> 告警：%s（%s/%s）%n%n", record.getAlertName(), record.getAlertSource(), record.getSeverity()));
        md.append(String.format("> 服务：%s ｜ 当前值：%s%n%n", record.getServiceName(), record.getAlertValue() == null ? "-" : record.getAlertValue()));
        md.append("\n---\n\n");
        md.append(String.format("### 🔍 根因（置信度 %s）%n%n", record.getConfidence() == null ? "-" : String.format("%.0f%%", record.getConfidence().doubleValue() * 100)));
        md.append(record.getRootCause()).append("\n\n");
        md.append("### 📋 分析\n\n").append(record.getAnalysisSummary()).append("\n\n");
        md.append("### ✅ 建议\n\n").append(record.getRecommendation()).append("\n\n");
        md.append("---\n\n");
        md.append("> ⚠️ AI 分析基于自动取证生成，仅供参考，请结合人工判断\n\n");

        String reportUrl = dashboardUrl + "/incident-investigation";
        try {
            List<DingtalkRobot> robots = dingtalkRobotService.listEnabled();
            if (robots.isEmpty()) {
                dingTalkClient.sendRobotActionCard("AI 事故调查报告", md.toString(), "查看完整报告", reportUrl, true);
                return;
            }
            for (DingtalkRobot robot : robots) {
                try {
                    dingTalkClient.sendRobotActionCard(robot.getWebhook(), robot.getSecret(),
                            "AI 事故调查报告", md.toString(), "查看完整报告", reportUrl, true);
                } catch (Exception e) {
                    log.warn("事故调查报告钉钉推送失败 (robot={}): {}", robot.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("事故调查报告钉钉推送失败，回退全局 Webhook: {}", e.getMessage());
            try {
                dingTalkClient.sendRobotActionCard("AI 事故调查报告", md.toString(), "查看完整报告", reportUrl, true);
            } catch (Exception ex) {
                log.error("事故调查报告钉钉推送最终失败: {}", ex.getMessage());
            }
        }
    }

    private void pushSse(IncidentMemory record) {
        try {
            Map<String, Object> payload = Map.of(
                    "type", "incident_investigation",
                    "id", record.getId(),
                    "alertName", record.getAlertName() == null ? "" : record.getAlertName(),
                    "status", record.getStatus(),
                    "rootCause", record.getRootCause() == null ? "" : record.getRootCause(),
                    "time", System.currentTimeMillis());
            alertPushService.pushAlert(payload);
        } catch (Exception e) {
            log.warn("事故调查 SSE 推送失败: {}", e.getMessage());
        }
    }

    /** 去重键：fingerprint 优先，自有告警无 fingerprint 时用 来源|类型|服务|30min桶 */
    static String buildDedupKey(InvestigationContext ctx) {
        if (ctx.getFingerprint() != null && !ctx.getFingerprint().isBlank()) {
            return "FP:" + ctx.getFingerprint();
        }
        long bucket = (ctx.getAlertTimeMs() == null ? System.currentTimeMillis() : ctx.getAlertTimeMs())
                / (30 * 60 * 1000L);
        return String.format("KEY:%s|%s|%s|%d", ctx.getAlertSource(), ctx.getAlertType(),
                ctx.getServiceName() == null ? "-" : ctx.getServiceName(), bucket);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
