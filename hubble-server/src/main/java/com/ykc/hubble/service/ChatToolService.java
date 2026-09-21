package com.ykc.hubble.service;

import com.aliyuncs.arms.model.v20190808.GetTraceResponse;
import com.aliyuncs.arms.model.v20190808.QueryMetricByPageResponse;
import com.aliyuncs.arms.model.v20190808.ListTraceAppsResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ykc.hubble.client.ArmsClient;
import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.AgentConfig;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.entity.LogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * AI 对话排查工具集：把 cwork-log（SLS 日志 + ARMS 链路）与 cwork-code（代码检索验证）
 * 的能力以 Function Calling 工具形式提供给对话模型。
 *
 * <p>工具清单：
 * <ul>
 *   <li>sls_query_logs —— 关键字/单号搜日志（支持 | SELECT 分析语句）</li>
 *   <li>sls_count_logs —— 统计命中条数</li>
 *   <li>list_arms_apps —— ARMS 应用列表（拿 pid）</li>
 *   <li>arms_api_metrics —— 接口性能（次数/平均RT/QPS/错误率）</li>
 *   <li>arms_trace_detail —— 单条链路 span 树（Redis/SQL 慢调用标记）</li>
 *   <li>search_code —— 代码工作区全文检索（验证根因）</li>
 *   <li>read_code —— 读取代码片段</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatToolService {

    private final SlsQueryClient slsQueryClient;
    private final ArmsClient armsClient;
    private final SlsConfig slsConfig;
    private final AgentConfig agentConfig;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "node_modules", "dist", "build", "out", ".idea", ".vscode",
            "logs", "log", ".next", "coverage", "vibe_images");
    private static final Set<String> BINARY_EXT = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg", ".webp", ".jar", ".class", ".war",
            ".zip", ".tar", ".gz", ".7z", ".pdf", ".doc", ".xls", ".woff", ".woff2", ".ttf",
            ".eot", ".mp4", ".mp3", ".mov", ".bin", ".so", ".dylib", ".dll", ".exe");
    private static final int MAX_LOG_LINES = 100;
    private static final int MAX_CODE_MATCHES = 40;
    private static final int MAX_TOOL_RESULT_CHARS = 12000;

    /** ARMS RpcType → 可读标签（13=Redis 14=SQL 是慢排查重点） */
    private static final Map<Integer, String> RPC_TYPE_LABELS = Map.ofEntries(
            Map.entry(0, "Local"), Map.entry(9, "HTTP客户端"), Map.entry(11, "HTTP服务端"),
            Map.entry(12, "SpringMVC"), Map.entry(13, "[Redis]"), Map.entry(14, "[SQL]"),
            Map.entry(20, "Dubbo"), Map.entry(23, "Kafka"), Map.entry(30, "RPC框架"),
            Map.entry(33, "异步"), Map.entry(40, "MQ"));

    // ==================== 工具 Schema ====================

    public List<JsonObject> toolSchemas() {
        List<JsonObject> schemas = new ArrayList<>();
        schemas.add(fn("sls_query_logs", "按关键字/单号/traceId 搜索 SLS 日志。query 支持 SLS 全文检索语法（多关键字用 and 连接，如 \"orderserver and BalanceCheck\"），也可带 | SELECT 分析语句。排查问题首选工具：有单号/traceId 直接搜。",
                Map.of(
                        "query", strParam("SLS 查询语句，如 \"单号\" 或 \"spring服务名 and 关键字\"，业务服务日志在 all 库用 spring.name 过滤"),
                        "env", strEnumParam("环境：prod/uat/test，默认 prod", List.of("prod", "uat", "test")),
                        "logstore", strParam("日志库名，默认 all（聚合库）。CTP车队查 ctp-*、设备协议查 device-*"),
                        "minutes", numParam("查询最近 N 分钟，默认 15"),
"line", numParam("返回条数，默认 30，最大 100"))));
        schemas.add(fn("sls_count_logs", "统计 SLS 查询命中的日志总数（不用返回明细时用，如判断某错误是整体还是个例）。",
                Map.of(
                        "query", strParam("SLS 查询语句"),
                        "env", strEnumParam("环境：prod/uat/test，默认 prod", List.of("prod", "uat", "test")),
                        "logstore", strParam("日志库名，默认 all"),
"minutes", numParam("查询最近 N 分钟，默认 15"))));
        schemas.add(fn("list_arms_apps", "获取 ARMS 应用监控列表（应用名 + pid）。查接口性能或链路前需要先拿 pid。",
                Map.of()));
        schemas.add(fn("arms_api_metrics", "查询指定应用的接口性能指标：调用次数、平均RT、QPS、错误率（按接口聚合）。",
                Map.of(
                        "pid", strParam("ARMS 应用 pid（先调 list_arms_apps 获取）"),
                        "minutes", numParam("查询最近 N 分钟，默认 15"),
                        "keyword", strParam("接口路径关键字过滤，可选，如 /api/order"))));
        schemas.add(fn("arms_trace_detail", "按 traceId 获取完整调用链路 span 树：上下游服务、每段耗时、Redis/SQL 调用内容，自动标记耗时点和错误 span。",
                Map.of(
                        "traceId", strParam("链路 traceId（日志的 trace 字段）"),
                        "tsMs", numParam("trace 发生时刻的毫秒时间戳（日志时间字段），可选但强烈建议传，能显著提高查询成功率"))));
        schemas.add(fn("search_code", "在本地代码工作区按关键字检索代码（类名/方法名/日志文案/配置项），返回 文件:行号:内容。用于根据日志证据验证根因。",
                Map.of(
                        "keyword", strParam("检索关键字，如类名、方法名、日志文案片段"),
                        "suffix", strParam("文件后缀过滤，可选，如 .java 或 .yml"))));
        schemas.add(fn("read_code", "读取代码文件的指定行范围，配合 search_code 的结果深入验证根因。",
                Map.of(
                        "path", strParam("文件相对工作区的路径（search_code 返回的相对路径）"),
                        "startLine", numParam("起始行号，默认 1"),
                        "endLine", numParam("结束行号，默认 startLine+80"))));
        schemas.add(fn("nacos_config_get", "获取指定 Nacos 配置内容（只读 GET）。排查问题时核对配置真值（开关/阈值/地址/参数）。",
                Map.of(
                        "env", strEnumParam("环境：dev/test/uat/prod/opendev，默认 test", List.of("dev", "opendev", "test", "uat", "prod")),
                        "dataId", strParam("配置 dataId，如 order-service.yml"),
                        "group", strParam("配置 group，默认 DEFAULT_GROUP"))));
        schemas.add(fn("nacos_config_search", "模糊搜索 Nacos 配置（按 dataId/group 关键字），用于不确定 dataId 时先定位。",
                Map.of(
                        "env", strEnumParam("环境：dev/test/uat/prod/opendev，默认 test", List.of("dev", "opendev", "test", "uat", "prod")),
                        "keyword", strParam("搜索关键字（匹配 dataId 或 group）"))));
        return schemas;
    }

    private JsonObject fn(String name, String description, Map<String, JsonObject> props) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        props.forEach(properties::add);
        for (String key : requiredKeys(name)) {
            if (props.containsKey(key)) required.add(key);
        }
        parameters.add("properties", properties);
        parameters.add("required", required);
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private List<String> requiredKeys(String tool) {
        return switch (tool) {
            case "sls_query_logs", "sls_count_logs" -> List.of("query");
            case "arms_api_metrics" -> List.of("pid");
            case "arms_trace_detail" -> List.of("traceId");
            case "search_code" -> List.of("keyword");
            case "read_code" -> List.of("path");
            default -> List.of();
        };
    }

    private JsonObject strParam(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", desc);
        return o;
    }

    private JsonObject strEnumParam(String desc, List<String> values) {
        JsonObject o = strParam(desc);
        JsonArray arr = new JsonArray();
        values.forEach(arr::add);
        o.add("enum", arr);
        return o;
    }

    private JsonObject numParam(String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "number");
        o.addProperty("description", desc);
        return o;
    }

    // ==================== 工具执行 ====================

    /** 给前端展示的工具执行状态文案 */
    public String statusLabel(String name, String argsJson) {
        Map<String, Object> args = parseArgs(argsJson);
        return switch (name == null ? "" : name) {
            case "sls_query_logs" -> String.format("正在查询 SLS 日志（%s/%s）: %s",
                    env(args), args.getOrDefault("logstore", "all"), args.getOrDefault("query", ""));
            case "sls_count_logs" -> String.format("正在统计日志数量（%s/%s）: %s",
                    env(args), args.getOrDefault("logstore", "all"), args.getOrDefault("query", ""));
            case "list_arms_apps" -> "正在获取 ARMS 应用列表";
            case "arms_api_metrics" -> "正在查询接口性能: pid=" + args.getOrDefault("pid", "");
            case "arms_trace_detail" -> "正在获取链路详情: " + args.getOrDefault("traceId", "");
            case "search_code" -> "正在代码库检索: " + args.getOrDefault("keyword", "");
            case "read_code" -> "正在读取代码: " + args.getOrDefault("path", "");
            case "nacos_config_get" -> "正在获取 Nacos 配置: " + args.getOrDefault("env", "test") + "/" + args.getOrDefault("dataId", "");
            case "nacos_config_search" -> "正在搜索 Nacos 配置: " + args.getOrDefault("env", "test") + "/" + args.getOrDefault("keyword", "");
            default -> "正在执行工具: " + name;
        };
    }

    /** 执行工具，返回给模型的结果文本（异常转错误文案，不抛出） */
    public String execute(String name, String argsJson) {
        Map<String, Object> args = parseArgs(argsJson);
        try {
            return switch (name == null ? "" : name) {
                case "sls_query_logs" -> slsQueryLogs(args);
                case "sls_count_logs" -> slsCountLogs(args);
                case "list_arms_apps" -> listArmsApps();
                case "arms_api_metrics" -> armsApiMetrics(args);
                case "arms_trace_detail" -> armsTraceDetail(args);
                case "search_code" -> searchCode(args);
                case "read_code" -> readCode(args);
                case "nacos_config_get" -> nacosConfigGet(args);
                case "nacos_config_search" -> nacosConfigSearch(args);
                default -> "未知工具: " + name;
            };
        } catch (Exception e) {
            log.warn("AI 工具执行失败: tool={}, args={}, error={}", name, argsJson, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    private Map<String, Object> parseArgs(String argsJson) {
        Map<String, Object> args = new HashMap<>();
        if (argsJson == null || argsJson.isBlank()) return args;
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(argsJson).getAsJsonObject();
            obj.entrySet().forEach(e -> {
                var v = e.getValue();
                if (v.isJsonPrimitive()) {
                    args.put(e.getKey(), v.getAsJsonPrimitive().isNumber()
                            ? v.getAsNumber() : v.getAsString());
                } else {
                    args.put(e.getKey(), v.toString());
                }
            });
        } catch (Exception e) {
            log.warn("工具参数解析失败: {}", argsJson);
        }
        return args;
    }

    private String env(Map<String, Object> args) {
        Object env = args.get("env");
        return env != null && !env.toString().isBlank() ? env.toString() : "prod";
    }

    /** env → SLS project（未配置映射时回落主 project） */
    private String resolveProject(String env) {
        String project = slsConfig.getProjects().get(env);
        return project != null && !project.isBlank() ? project : slsConfig.getProject();
    }

    private String slsQueryLogs(Map<String, Object> args) {
        String project = resolveProject(env(args));
        if (project == null || project.isBlank()) {
            return "SLS project 未配置（环境变量 ALIYUN_SLS_PROJECT 或 SLS_PROJECT_" + env(args).toUpperCase() + "）";
        }
        String logstore = str(args, "logstore", "all");
        String query = str(args, "query", "*");
        long minutes = num(args, "minutes", 15);
        int line = (int) Math.min(num(args, "line", 30), MAX_LOG_LINES);
        long to = System.currentTimeMillis() / 1000;
        long from = to - (long) minutes * 60;

        List<LogEntry> logs = slsQueryClient.queryLogstore(project, logstore, query, from, to, 0, line);
        if (logs.isEmpty()) {
            return String.format("无结果（env=%s, 库=%s, 最近%d分钟, query=%s）。建议：调整时间范围/检查服务名（用 spring.name 全文搜）/换专属库",
                    env(args), logstore, minutes, query);
        }
        StringBuilder sb = new StringBuilder(String.format("共返回 %d 条（env=%s, 库=%s, 最近%d分钟）：\n",
                logs.size(), env(args), logstore, minutes));
        for (LogEntry entry : logs) {
            sb.append(String.format("%s | %s | %s | trace=%s | %s\n",
                    entry.getTime(),
                    entry.getContainerName() != null ? entry.getContainerName() : "-",
                    entry.getLevel() != null ? entry.getLevel() : "-",
                    entry.getTrace() != null ? entry.getTrace() : "-",
                    truncate(entry.getMessage(), 300)));
        }
        return sb.toString();
    }

    private String slsCountLogs(Map<String, Object> args) {
        String project = resolveProject(env(args));
        if (project == null || project.isBlank()) {
            return "SLS project 未配置";
        }
        String logstore = str(args, "logstore", "all");
        String query = str(args, "query", "*");
        long minutes = num(args, "minutes", 15);
        long to = System.currentTimeMillis() / 1000;
        long from = to - (long) minutes * 60;
        long count = slsQueryClient.countLogstore(project, logstore, query, from, to);
        return String.format("命中 %d 条（env=%s, 库=%s, 最近%d分钟, query=%s）",
                count, env(args), logstore, minutes, query);
    }

    private String listArmsApps() throws Exception {
        ListTraceAppsResponse resp = armsClient.listApps();
        StringBuilder sb = new StringBuilder("ARMS 应用列表（ appName | pid ）:\n");
        if (resp.getTraceApps() != null) {
            resp.getTraceApps().stream()
                    .limit(80)
                    .forEach(app -> sb.append(String.format("%s | %s\n",
                            app.getAppName(), app.getPid())));
            if (resp.getTraceApps().size() > 80) {
                sb.append("... 共 ").append(resp.getTraceApps().size()).append(" 个\n");
            }
        }
        return sb.toString();
    }

    private String armsApiMetrics(Map<String, Object> args) throws Exception {
        String pid = str(args, "pid", "");
        long minutes = num(args, "minutes", 15);
        String keyword = str(args, "keyword", "");
        long toMs = System.currentTimeMillis();
        long fromMs = toMs - (long) minutes * 60 * 1000;

        QueryMetricByPageResponse resp = armsClient.queryMetricsWithDimension(
                "appstat.incall", List.of("count", "rt", "qps", "errorrate"),
                fromMs, toMs, pid, 60000, List.of("rpc"));
        StringBuilder sb = new StringBuilder(String.format("接口性能（pid=%s, 最近%d分钟）:\n接口 | 次数 | 平均RT(ms) | QPS | 错误率%%\n", pid, minutes));
        int rows = 0;
        if (resp.getData() != null && resp.getData().getItems() != null) {
            for (Object itemObj : resp.getData().getItems()) {
                if (!(itemObj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<Object, Object> item = (Map<Object, Object>) itemObj;
                // ARMS 返回为平铺结构（rpc/rt/count 直接在 item 顶层）且值均为 String，
                // 无 dimensions/measures 嵌套（旧解析恒空表）
                Object rpcObj = item.get("rpc");
                if (rpcObj == null) continue;
                String rpc = rpcObj.toString();
                if (!keyword.isBlank() && !rpc.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                sb.append(String.format("%s | %s | %s | %s | %s\n",
                        rpc,
                        item.getOrDefault("count", "-"),
                        item.getOrDefault("rt", "-"),
                        item.getOrDefault("qps", "-"),
                        item.getOrDefault("errorrate", "-")));
                if (++rows >= 50) break;
            }
        }
        if (rows == 0) sb.append("无数据（pid 是否有效？时间范围是否有流量？）\n");
        return sb.toString();
    }

    private String armsTraceDetail(Map<String, Object> args) throws Exception {
        String traceId = str(args, "traceId", "");
        long toMs = System.currentTimeMillis();
        long fromMs;
        Object tsMs = args.get("tsMs");
        if (tsMs instanceof Number n && n.longValue() > 0) {
            fromMs = n.longValue() - 10 * 60 * 1000;
            toMs = n.longValue() + 10 * 60 * 1000;
        } else {
            fromMs = toMs - 60 * 60 * 1000;
        }
        GetTraceResponse resp = armsClient.getTrace(traceId, fromMs, toMs);
        List<GetTraceResponse.Span> spans = resp.getSpans();
        if (spans == null || spans.isEmpty()) {
            return "链路查询无结果（traceId=" + traceId + "）。建议：确认 traceId 正确、传入 tsMs（日志时间）、或链路已过保留期";
        }
        return renderSpanTree(spans);
    }

    /** span 列表 → 缩进树文本，标记 Redis/SQL 调用、耗时点、错误 span */
    String renderSpanTree(List<GetTraceResponse.Span> spans) {
        Map<String, List<GetTraceResponse.Span>> children = new HashMap<>();
        Set<String> spanIds = new java.util.HashSet<>();
        spans.forEach(s -> spanIds.add(s.getSpanId()));
        List<GetTraceResponse.Span> roots = new ArrayList<>();
        for (GetTraceResponse.Span s : spans) {
            String parent = s.getParentSpanId();
            if (parent == null || parent.isBlank() || !spanIds.contains(parent)) {
                roots.add(s);
            } else {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(s);
            }
        }
        // 耗时点标记：全链路最慢的 3 个 span + 耗时>50ms 的 Redis/SQL span
        List<GetTraceResponse.Span> slowest = spans.stream()
                .filter(s -> s.getDuration() != null)
                .sorted(Comparator.comparingLong((GetTraceResponse.Span s) -> -s.getDuration()))
                .limit(3)
                .toList();

        StringBuilder sb = new StringBuilder(String.format("链路 span 树（共 %d 个 span，<<< 为耗时点）：\n", spans.size()));
        List<String> lines = new ArrayList<>();
        for (GetTraceResponse.Span root : roots) {
            renderSpan(root, children, slowest, 0, lines);
        }
        for (int i = 0; i < lines.size(); i++) {
            if (i >= 150) {
                sb.append("...（超过 150 行已截断）\n");
                break;
            }
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    private void renderSpan(GetTraceResponse.Span span, Map<String, List<GetTraceResponse.Span>> children,
                            List<GetTraceResponse.Span> slowest, int depth, List<String> lines) {
        String typeLabel = span.getRpcType() != null
                ? RPC_TYPE_LABELS.getOrDefault(span.getRpcType(), "Rpc" + span.getRpcType()) : "";
        long duration = span.getDuration() != null ? span.getDuration() : 0;
        StringBuilder line = new StringBuilder();
        line.append("  ".repeat(Math.max(0, depth)));
        line.append("├─ ").append(typeLabel.isBlank() ? "" : typeLabel + " ");
        line.append(span.getServiceName() != null ? span.getServiceName() : "-").append(" : ");
        line.append(truncate(span.getOperationName(), 120));
        line.append(String.format(" (%d ms)", duration));
        if (isErrorSpan(span)) line.append(" ⛔ERROR");
        if (slowest.contains(span)) line.append(" <<< 全链路耗时点");
        else if ((span.getRpcType() != null && (span.getRpcType() == 13 || span.getRpcType() == 14)) && duration > 50) {
            line.append(" <<< 耗时点");
        }
        lines.add(line.toString());
        List<GetTraceResponse.Span> kids = children.getOrDefault(span.getSpanId(), new ArrayList<>());
        kids.sort(Comparator.comparingLong(s -> s.getTimestamp() != null ? s.getTimestamp() : 0));
        for (GetTraceResponse.Span kid : kids) {
            renderSpan(kid, children, slowest, depth + 1, lines);
        }
    }

    private boolean isErrorSpan(GetTraceResponse.Span span) {
        if (span.getResultCode() != null && !span.getResultCode().isBlank()
                && !span.getResultCode().equals("0") && !span.getResultCode().equals("200")
                && !span.getResultCode().equalsIgnoreCase("SUCCESS")) {
            return true;
        }
        if (span.getTagEntryList() != null) {
            for (var tag : span.getTagEntryList()) {
                String key = tag.getKey();
                String value = tag.getValue();
                if ("otel.status_code".equals(key) && "ERROR".equalsIgnoreCase(value)) return true;
                if ("error".equals(key) && "true".equalsIgnoreCase(value)) return true;
            }
        }
        return false;
    }

    // ==================== 代码检索（cwork-code 能力） ====================

    private Path workspaceRoot() {
        String ws = agentConfig.getCodeWorkspace();
        if (ws == null || ws.isBlank()) return null;
        Path p = Paths.get(ws);
        return Files.isDirectory(p) ? p : null;
    }

    private String searchCode(Map<String, Object> args) throws IOException {
        Path root = workspaceRoot();
        if (root == null) {
            return "代码工作区未配置或不存在（配置项 agent.code-workspace / 环境变量 HUBBLE_CODE_WORKSPACE），无法检索代码";
        }
        String keyword = str(args, "keyword", "");
        if (keyword.isBlank()) return "keyword 不能为空";
        String suffix = str(args, "suffix", "");
        String kwLower = keyword.toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder();
        int matches = 0;
        try (Stream<Path> stream = Files.walk(root, 12)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p))
                    .filter(p -> suffix.isBlank() || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .toList();
            for (Path file : files) {
                if (matches >= MAX_CODE_MATCHES) break;
                if (Files.size(file) > 2 * 1024 * 1024) continue;
                String content;
                try {
                    content = Files.readString(file);
                } catch (Exception e) {
                    continue; // 二进制/解码失败跳过
                }
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length && matches < MAX_CODE_MATCHES; i++) {
                    if (lines[i].toLowerCase(Locale.ROOT).contains(kwLower)) {
                        String rel = root.relativize(file).toString();
                        sb.append(String.format("%s:%d: %s\n", rel, i + 1, truncate(lines[i].trim(), 200)));
                        matches++;
                    }
                }
            }
        }
        if (matches == 0) return "工作区未检索到 \"" + keyword + "\"（可换关键字：类名/方法名/日志文案）";
        return "检索到 " + matches + " 处：\n" + sb;
    }

    private boolean isSkipped(Path file) {
        for (Path part : file) {
            if (SKIP_DIRS.contains(part.toString())) return true;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        return BINARY_EXT.contains(ext);
    }

    private String readCode(Map<String, Object> args) throws IOException {
        Path root = workspaceRoot();
        if (root == null) {
            return "代码工作区未配置（agent.code-workspace / HUBBLE_CODE_WORKSPACE）";
        }
        String relPath = str(args, "path", "");
        if (relPath.isBlank()) return "path 不能为空";
        Path file = root.resolve(relPath).normalize();
        // 防路径穿越：必须仍在工作区内
        if (!file.startsWith(root.toAbsolutePath().normalize())) {
            return "非法路径: " + relPath;
        }
        if (!Files.isRegularFile(file)) return "文件不存在: " + relPath;

        List<String> allLines = Files.readAllLines(file);
        int start = (int) Math.max(1, num(args, "startLine", 1));
        int end = Math.min(allLines.size(), start + 80);
        if (args.get("endLine") instanceof Number n) {
            end = Math.max(start, Math.min(n.intValue(), allLines.size()));
        }
        end = Math.min(end, start + 500);

        StringBuilder sb = new StringBuilder(String.format("%s（%d-%d/%d 行）:\n", relPath, start, end, allLines.size()));
        for (int i = start; i <= end; i++) {
            sb.append(i).append(": ").append(allLines.get(i - 1)).append('\n');
        }
        return sb.toString();
    }

    private String nacosConfigGet(Map<String, Object> args) throws IOException {
        String script = agentConfig.getNacosScriptPath();
        if (script.isBlank() || !Files.isRegularFile(Path.of(script))) {
            return "Nacos 查询脚本未配置或不存在（agent.nacos-script-path）";
        }
        String env = str(args, "env", "test");
        String dataId = str(args, "dataId", "");
        if (dataId.isBlank()) return "dataId 不能为空";
        String group = str(args, "group", "DEFAULT_GROUP");
        return execShell(script, List.of(env, "get", dataId, group));
    }

    private String nacosConfigSearch(Map<String, Object> args) throws IOException {
        String script = agentConfig.getNacosScriptPath();
        if (script.isBlank() || !Files.isRegularFile(Path.of(script))) {
            return "Nacos 查询脚本未配置或不存在（agent.nacos-script-path）";
        }
        String env = str(args, "env", "test");
        String keyword = str(args, "keyword", "");
        if (keyword.isBlank()) return "keyword 不能为空";
        return execShell(script, List.of(env, "search", keyword));
    }

    private String execShell(String script, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(script);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (var is = p.getInputStream()) {
            output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "Nacos 查询超时（15s）";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Nacos 查询被中断";
        }
        return output.isBlank() ? "（无结果）" : output;
    }

    private String str(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : def;
    }

    private long num(Map<String, Object> args, String key, long def) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.longValue();
        try {
            return v != null ? Long.parseLong(v.toString()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
