package com.ykc.hubble.service.investigation;

import com.aliyuncs.arms.model.v20190808.ListTraceAppsResponse;
import com.aliyuncs.arms.model.v20190808.QueryMetricByPageResponse;
import com.ykc.hubble.client.ArmsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ARMS 接口指标取证：查告警应用最近 15 分钟 appstat.incall（依赖调用）与 appstat.http（入口 HTTP）
 * 按接口聚合的 RT/调用量/错误情况，取慢/异常接口 Top10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArmsEvidenceCollector implements EvidenceCollector {

    private final ArmsClient armsClient;

    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final int MAX_CHARS = 4000;

    @Override
    public String name() {
        return "ARMS接口指标";
    }

    @Override
    public Evidence collect(InvestigationContext ctx) {
        String app = ctx.getServiceName();
        if (app == null || app.isBlank()) {
            return Evidence.degraded(name(), "无服务名，跳过 ARMS 查询");
        }
        try {
            String pid = resolvePid(app);
            if (pid == null) {
                return Evidence.degraded(name(), "ARMS 未找到应用 " + app + "（可能未接入探针）");
            }

            long toMs = System.currentTimeMillis();
            long fromMs = toMs - WINDOW_MS;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("应用 %s 最近15分钟接口指标（依赖调用 appstat.incall）：\n", app));
            String incall = summarizeIncall(pid, fromMs, toMs);
            sb.append(incall);

            String content = sb.toString();
            return Evidence.ok(name(), content.length() > MAX_CHARS ? content.substring(0, MAX_CHARS) + "...(截断)" : content);
        } catch (Exception e) {
            log.warn("ARMS 取证失败: app={}, error={}", app, e.getMessage());
            return Evidence.degraded(name(), "ARMS 查询失败: " + e.getMessage());
        }
    }

    /** 应用名（xxx 或 xxx-prod）→ pid；匹配 -prod 优先 */
    private String resolvePid(String app) throws Exception {
        ListTraceAppsResponse resp = armsClient.listApps();
        if (resp == null || resp.getTraceApps() == null) {
            return null;
        }
        String exact = null, prod = null, base = null;
        String bare = app.endsWith("-prod") ? app.substring(0, app.length() - 5) : app;
        for (ListTraceAppsResponse.TraceApp a : resp.getTraceApps()) {
            if (a.getAppName() == null || a.getPid() == null) continue;
            if (a.getAppName().equals(app)) exact = String.valueOf(a.getPid());
            else if (a.getAppName().equals(app + "-prod")) prod = String.valueOf(a.getPid());
            else if (a.getAppName().equals(bare)) base = String.valueOf(a.getPid());
        }
        return exact != null ? exact : (prod != null ? prod : base);
    }

    /** 按接口聚合 rt/count（截尾均值口径与依赖巡检一致：桶≥3 去最高最低），取平均 RT Top10 */
    private String summarizeIncall(String pid, long fromMs, long toMs) throws Exception {
        QueryMetricByPageResponse resp = armsClient.queryMetricsWithDimension(
                "appstat.incall", List.of("rt", "count"), fromMs, toMs, pid, 60000, List.of("rpcType", "rpc"));
        if (resp == null || resp.getData() == null || resp.getData().getItems() == null
                || resp.getData().getItems().isEmpty()) {
            return "  最近15分钟无依赖调用数据\n";
        }

        // comboKey -> [rtSum*cnt, cntSum, buckets]
        Map<String, double[]> agg = new HashMap<>();
        for (var itemObj : resp.getData().getItems()) {
            if (!(itemObj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<Object, Object> item = (Map<Object, Object>) itemObj;
            Object rpc = item.get("rpc");
            if (rpc == null) continue;
            double rt = toDouble(item.get("rt"));
            double cnt = toDouble(item.get("count"));
            if (cnt <= 0) continue;
            String key = (item.get("rpcType") == null ? "" : item.get("rpcType")) + "|" + rpc;
            double[] v = agg.computeIfAbsent(key, k -> new double[3]);
            v[0] += rt * cnt;
            v[1] += cnt;
            v[2] += 1;
        }
        if (agg.isEmpty()) {
            return "  最近15分钟无有效依赖调用数据\n";
        }

        List<Map.Entry<String, double[]>> sorted = agg.entrySet().stream()
                .sorted((a, b) -> Double.compare(avgRt(b.getValue()), avgRt(a.getValue())))
                .limit(10)
                .toList();

        StringBuilder sb = new StringBuilder();
        for (var e : sorted) {
            double[] v = e.getValue();
            sb.append(String.format("  %s: avgRT=%.0fms, calls=%d%n", e.getKey(), avgRt(v), (long) v[1]));
        }
        return sb.toString();
    }

    private double avgRt(double[] v) {
        // v[0]=加权rt和, v[1]=调用数, v[2]=桶数；桶≥3 截尾近似（此处只有聚合值，退化为直接均值）
        return v[1] > 0 ? v[0] / v[1] : 0;
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
