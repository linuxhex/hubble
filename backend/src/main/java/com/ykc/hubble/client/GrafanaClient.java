package com.ykc.hubble.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Grafana Prometheus 查询客户端
 * 通过 Grafana HTTP API (/api/ds/query) 查 PromQL，获取 Pod/Node 监控数据。
 */
@Slf4j
@Component
public class GrafanaClient {

    @Value("${grafana.url:https://graf.ykccn.net}")
    private String grafanaUrl;

    @Value("${grafana.user:caomunian}")
    private String grafanaUser;

    @Value("${grafana.pass:}")
    private String grafanaPass;

    @Value("${grafana.ds-uid:6A__NzsMk}")
    private String dsUid;

    @Value("${grafana.node-ds-uid:cem0jt0mij668b}")
    private String nodeDsUid;

    @Value("${grafana.aliyun-ds-uid:l9II0lm4z}")
    private String aliyunDsUid;

    @Value("${grafana.biz-ds-uid:Ufpny5tSz}")
    private String bizDsUid;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    /**
     * 执行 PromQL instant 查询（使用默认数据源）
     */
    public List<Map<String, Object>> queryInstant(String promql) {
        return queryInstant(promql, dsUid);
    }

    /**
     * 执行 PromQL instant 查询（指定数据源）
     */
    public List<Map<String, Object>> queryInstant(String promql, String targetDsUid) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String body = String.format(
                    "{\"queries\":[{\"refId\":\"A\",\"datasource\":{\"type\":\"prometheus\",\"uid\":\"%s\"},\"expr\":%s,\"instant\":true}],\"from\":\"now-1h\",\"to\":\"now\"}",
                    targetDsUid, JSON.toJSONString(promql));

            String auth = Base64.getEncoder().encodeToString(
                    (grafanaUser + ":" + grafanaPass).getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaUrl + "/api/ds/query"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + auth)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Grafana 查询失败: status={}, promql={}", resp.statusCode(), promql);
                return results;
            }

            JSONObject json = JSON.parseObject(resp.body());
            JSONObject resultA = json.getJSONObject("results").getJSONObject("A");
            if (resultA == null) return results;

            JSONArray frames = resultA.getJSONArray("frames");
            if (frames == null) return results;

            for (int i = 0; i < frames.size(); i++) {
                JSONObject frame = frames.getJSONObject(i);
                JSONObject schema = frame.getJSONObject("schema");
                JSONObject data = frame.getJSONObject("data");
                if (schema == null || data == null) continue;

                JSONArray fields = schema.getJSONArray("fields");
                JSONArray values = data.getJSONArray("values");
                if (fields == null || fields.isEmpty() || values == null || values.isEmpty()) continue;

                // Grafana 返回两个 field：第一个是 Time（时间戳），第二个是 Value（带 labels）
                // 找 value field（有 labels 的那个）
                int valueFieldIdx = -1;
                for (int f = 0; f < fields.size(); f++) {
                    JSONObject field = fields.getJSONObject(f);
                    JSONObject labels = field.getJSONObject("labels");
                    if (labels != null && !labels.isEmpty()) {
                        valueFieldIdx = f;
                        break;
                    }
                }
                if (valueFieldIdx == -1) valueFieldIdx = fields.size() - 1;
                if (valueFieldIdx < 0) continue;

                JSONObject field = fields.getJSONObject(valueFieldIdx);
                JSONObject labels = field.getJSONObject("labels");
                JSONArray valArr = values.getJSONArray(valueFieldIdx);
                if (valArr == null || valArr.isEmpty()) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                if (labels != null) {
                    for (String key : labels.keySet()) {
                        item.put(key, labels.getString(key));
                    }
                }
                item.put("value", valArr.get(0));
                results.add(item);
            }
        } catch (Exception e) {
            log.warn("Grafana 查询异常: promql={}, error={}", promql, e.getMessage());
        }
        return results;
    }

    /**
     * 查询并返回 double 值列表（带 labels）
     */
    public List<Map<String, Object>> queryMetric(String promql) {
        return queryInstant(promql);
    }

    public String getNodeDsUid() {
        return nodeDsUid;
    }

    public String getAliyunDsUid() {
        return aliyunDsUid;
    }

    public String getDsUid() {
        return dsUid;
    }

    public String getBizDsUid() {
        return bizDsUid;
    }
}
