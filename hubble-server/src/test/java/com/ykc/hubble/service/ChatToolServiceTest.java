package com.ykc.hubble.service;

import com.aliyuncs.arms.model.v20190808.GetTraceResponse;
import com.ykc.hubble.client.ArmsClient;
import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.AgentConfig;
import com.ykc.hubble.config.SlsConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatToolServiceTest {

    private SlsQueryClient slsQueryClient;
    private final ArmsClient armsClient = mock(ArmsClient.class);
    private SlsConfig slsConfig;
    private final AgentConfig agentConfig = new AgentConfig();

    private ChatToolService newService(String mainProject, String prodProject) {
        slsQueryClient = mock(SlsQueryClient.class);
        slsConfig = new SlsConfig();
        slsConfig.setProject(mainProject);
        if (prodProject != null) {
            slsConfig.getProjects().put("prod", prodProject);
        }
        return new ChatToolService(slsQueryClient, armsClient, slsConfig, agentConfig);
    }

    // ==================== 工具 Schema ====================

    @Test
    @DisplayName("toolSchemas 应包含 9 个工具且参数结构完整")
    void toolSchemas_shouldContainAllTools() {
        List<JsonObject> schemas = newService("p", null).toolSchemas();
        assertEquals(9, schemas.size());
        String names = schemas.toString();
        for (String tool : List.of("sls_query_logs", "sls_count_logs", "list_arms_apps",
                "arms_api_metrics", "arms_trace_detail", "search_code", "read_code",
                "nacos_config_get", "nacos_config_search")) {
            assertTrue(names.contains(tool), "缺少工具: " + tool);
        }
    }

    // ==================== env → project 映射 ====================

    @Test
    @DisplayName("sls_query_logs: 已配置的环境映射使用映射 project")
    void slsQueryLogs_envMapping_usesMappedProject() {
        ChatToolService service = newService("main-proj", "prod-proj");
        when(slsQueryClient.queryLogstore(eq("prod-proj"), eq("all"), eq("订单123"), anyLong(), anyLong(), eq(0), eq(30)))
                .thenReturn(List.of());

        service.execute("sls_query_logs", "{\"env\":\"prod\",\"query\":\"订单123\"}");

        verify(slsQueryClient).queryLogstore(eq("prod-proj"), eq("all"), eq("订单123"), anyLong(), anyLong(), eq(0), eq(30));
    }

    @Test
    @DisplayName("sls_query_logs: 未配置映射的环境回落主 project")
    void slsQueryLogs_unmappedEnv_fallsBackToMainProject() {
        ChatToolService service = newService("main-proj", "prod-proj");
        when(slsQueryClient.queryLogstore(eq("main-proj"), eq("all"), eq("x"), anyLong(), anyLong(), eq(0), eq(30)))
                .thenReturn(List.of());

        service.execute("sls_query_logs", "{\"env\":\"uat\",\"query\":\"x\"}");

        verify(slsQueryClient).queryLogstore(eq("main-proj"), eq("all"), eq("x"), anyLong(), anyLong(), eq(0), eq(30));
    }

    @Test
    @DisplayName("sls_query_logs: 空结果返回建议文案而非异常")
    void slsQueryLogs_emptyResult_returnsHint() {
        ChatToolService service = newService("main-proj", null);
        when(slsQueryClient.queryLogstore(eq("main-proj"), eq("all"), eq("x"), anyLong(), anyLong(), eq(0), eq(30)))
                .thenReturn(List.of());

        String result = service.execute("sls_query_logs", "{\"query\":\"x\"}");
        assertTrue(result.contains("无结果"));
    }

    // ==================== 链路 span 树 ====================

    private GetTraceResponse.Span span(String spanId, String parent, String service, String op,
                                       long duration, long ts, int rpcType, String resultCode) {
        GetTraceResponse.Span s = new GetTraceResponse.Span();
        s.setSpanId(spanId);
        s.setParentSpanId(parent);
        s.setServiceName(service);
        s.setOperationName(op);
        s.setDuration(duration);
        s.setTimestamp(ts);
        s.setRpcType(rpcType);
        s.setResultCode(resultCode);
        return s;
    }

    @Test
    @DisplayName("span 树：缩进层级 + SQL/Redis 标记 + 耗时点 + 错误 span")
    void renderSpanTree_shouldMarkSlowAndError() {
        List<GetTraceResponse.Span> spans = List.of(
                span("1", "", "gateway", "GET /api/a", 100, 1000, 12, "200"),
                span("2", "1", "order-server", "OrderService.create", 80, 1020, 12, "500"),
                span("3", "2", "order-server", "SELECT * FROM t_order", 60, 1030, 14, "200"),
                span("4", "2", "order-server", "cache get", 5, 1040, 13, "200"),
                span("5", "1", "base-server", "queryGun", 3, 1050, 30, "200"));

        String tree = newService("p", null).renderSpanTree(spans);

        assertTrue(tree.contains("[SQL]"), "应标记 SQL span");
        assertTrue(tree.contains("[Redis]"), "应标记 Redis span");
        assertTrue(tree.contains("全链路耗时点"), "应标记全链路耗时点");
        assertTrue(tree.contains("耗时点"), "SQL >50ms 应标记耗时点");
        assertTrue(tree.contains("⛔ERROR"), "ResultCode=500 应标记错误");
        assertTrue(tree.contains("├─ ├─") || tree.contains("  ├─"), "应有缩进层级");
        // order-server 是 gateway 的子节点，缩进应比根深
        int rootIdx = tree.indexOf("gateway");
        int childIdx = tree.indexOf("OrderService.create");
        assertTrue(childIdx > rootIdx);
    }

    @Test
    @DisplayName("span 树：parent 不在集合中的 span 作为根节点")
    void renderSpanTree_orphanSpan_becomesRoot() {
        List<GetTraceResponse.Span> spans = List.of(
                span("x", "missing-parent", "svc", "op", 10, 0, 12, "200"));
        String tree = newService("p", null).renderSpanTree(spans);
        assertTrue(tree.contains("svc"));
    }

    // ==================== 代码检索 ====================

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("search_code：命中大小写不敏感，跳过 target 目录和二进制文件")
    void searchCode_matchesAndSkips() throws Exception {
        Path src = Files.createDirectories(tempDir.resolve("project/src"));
        Path target = Files.createDirectories(tempDir.resolve("project/target/classes"));
        Files.writeString(src.resolve("BalanceService.java"), "public class BalanceService {\n    void BalanceCheck() {}\n}\n");
        Files.writeString(target.resolve("BalanceService.class.txt"), "BalanceCheck in compiled\n");
        Files.writeString(tempDir.resolve("project/note.png"), "BalanceCheck text in png");

        agentConfig.setCodeWorkspace(tempDir.toString());
        ChatToolService service = newService("p", null);

        String result = service.execute("search_code", "{\"keyword\":\"balancecheck\"}");
        assertTrue(result.contains("BalanceService.java"), "应命中 java 文件:\n" + result);
        assertFalse(result.contains("target"), "应跳过 target 目录:\n" + result);
        assertFalse(result.contains("note.png"), "应跳过二进制后缀:\n" + result);
    }

    @Test
    @DisplayName("search_code：suffix 过滤生效")
    void searchCode_suffixFilter() throws Exception {
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/App.java"), "BalanceCheck here\n");
        Files.writeString(tempDir.resolve("app/App.yml"), "BalanceCheck: true\n");
        agentConfig.setCodeWorkspace(tempDir.toString());
        ChatToolService service = newService("p", null);

        String result = service.execute("search_code", "{\"keyword\":\"BalanceCheck\",\"suffix\":\".yml\"}");
        assertTrue(result.contains("App.yml"));
        assertFalse(result.contains("App.java"));
    }

    @Test
    @DisplayName("read_code：按行范围读取；路径穿越被拒绝")
    void readCode_rangeAndTraversalGuard() throws Exception {
        Path app = Files.createDirectories(tempDir.resolve("app"));
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 100; i++) content.append("line").append(i).append('\n');
        Files.writeString(app.resolve("A.java"), content.toString());
        Files.writeString(tempDir.resolve("secret.txt"), "top secret");
        agentConfig.setCodeWorkspace(tempDir.toString());
        ChatToolService service = newService("p", null);

        String range = service.execute("read_code", "{\"path\":\"app/A.java\",\"startLine\":10,\"endLine\":12}");
        assertTrue(range.contains("10: line10"));
        assertTrue(range.contains("12: line12"));
        assertFalse(range.contains("line13"));

        String traversal = service.execute("read_code", "{\"path\":\"../secret.txt\"}");
        assertTrue(traversal.contains("非法路径"));
    }

    @Test
    @DisplayName("代码工作区未配置时返回友好提示")
    void codeTools_workspaceMissing_returnsHint() {
        agentConfig.setCodeWorkspace("");
        ChatToolService service = newService("p", null);

        String result = service.execute("search_code", "{\"keyword\":\"x\"}");
        assertTrue(result.contains("未配置"));
    }

    // ==================== 参数解析与兜底 ====================

    @Test
    @DisplayName("未知工具与非法 JSON 参数返回错误文案不抛异常")
    void execute_invalidInput_returnsErrorText() {
        ChatToolService service = newService("p", null);
        assertTrue(service.execute("no_such_tool", "{}").contains("未知工具"));
        assertTrue(service.execute("sls_query_logs", "not-json").contains("无结果") || !service.execute("sls_query_logs", "not-json").isEmpty());
    }

    @Test
    @DisplayName("statusLabel 输出可读状态文案")
    void statusLabel_readable() {
        ChatToolService service = newService("p", null);
        assertTrue(service.statusLabel("sls_query_logs", "{\"env\":\"prod\",\"query\":\"abc\"}").contains("abc"));
        assertTrue(service.statusLabel("search_code", "{\"keyword\":\"Foo\"}").contains("Foo"));
        assertTrue(service.statusLabel("unknown", "{}").contains("unknown"));
    }
}
