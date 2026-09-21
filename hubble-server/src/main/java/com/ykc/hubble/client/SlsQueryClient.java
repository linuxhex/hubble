package com.ykc.hubble.client;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.request.GetLogsRequest;
import com.aliyun.openservices.log.response.GetHistogramsResponse;
import com.aliyun.openservices.log.response.GetLogsResponse;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.entity.NodeQueryResult;
import com.ykc.hubble.entity.TraceNode;
import com.ykc.hubble.util.QueryTemplateParser;
import com.ykc.hubble.util.SlsClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SLS查询客户端
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlsQueryClient {

    private final SlsClientFactory slsClientFactory;
    private final QueryTemplateParser queryTemplateParser;
    private final SlsConfig slsConfig;
    private final Executor queryExecutor;

    public boolean isDisabled() {
        return slsConfig.getAccessKeyId() == null || slsConfig.getAccessKeyId().isBlank();
    }

    /**
     * 并行查询多个节点
     *
     * @param nodes 节点列表
     * @param variables 查询变量值
     * @param fromTime 开始时间（Unix时间戳，秒）
     * @param toTime 结束时间（Unix时间戳，秒）
     * @return 节点名称到查询结果的Map
     */
    public Map<String, NodeQueryResult> queryNodes(
            List<TraceNode> nodes,
            Map<String, String> variables,
            long fromTime,
            long toTime) {

        // 创建并行查询任务
        List<CompletableFuture<NodeQueryResult>> futures = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return queryNode(node, variables, fromTime, toTime);
                            } catch (Exception e) {
                                log.error("查询节点失败: {}", node.getName(), e);
                                return NodeQueryResult.failed(node.getName(), e.getMessage());
                            }
                        },
                        queryExecutor
                ))
                .collect(Collectors.toList());

        // 等待所有查询完成或超时
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            allOf.get(slsConfig.getQuery().getGlobalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("部分查询超时或失败", e);
        }

        // 收集结果
        return futures.stream()
                .map(f -> {
                    try {
                        return f.getNow(NodeQueryResult.failed("unknown", "查询未完成"));
                    } catch (Exception e) {
                        return NodeQueryResult.failed("unknown", e.getMessage());
                    }
                })
                .collect(Collectors.toMap(
                        NodeQueryResult::getNodeName,
                        result -> result,
                        (r1, r2) -> r1 // 如果有重复，保留第一个
                ));
    }

    /**
     * 查询单个节点
     *
     * @param node 节点
     * @param variables 查询变量值
     * @param fromTime 开始时间（Unix时间戳，秒）
     * @param toTime 结束时间（Unix时间戳，秒）
     * @return 查询结果
     */
    private NodeQueryResult queryNode(
            TraceNode node,
            Map<String, String> variables,
            long fromTime,
            long toTime) {

        try {
            // 1. 替换查询模板中的变量
            String query = queryTemplateParser.replaceVariables(node.getQueryTemplate(), variables);
            log.debug("节点[{}]查询语句: {}, 时间范围: {} - {}", node.getName(), query, fromTime, toTime);

            // 2. 验证时间戳是否在合理范围内（秒级时间戳）
            if (fromTime < 0 || toTime < 0) {
                log.error("时间戳不能为负数: fromTime={}, toTime={}", fromTime, toTime);
                throw new IllegalArgumentException("时间戳不能为负数");
            }
            if (fromTime > Integer.MAX_VALUE || toTime > Integer.MAX_VALUE) {
                log.error("时间戳超出int范围，可能传递了毫秒级时间戳: fromTime={}, toTime={}", fromTime, toTime);
                throw new IllegalArgumentException("时间戳应为秒级，不应超过int最大值");
            }

            // 3. 调用SLS API（使用全局配置的project）
            Client client = slsClientFactory.getClient();
            GetLogsRequest request = new GetLogsRequest(
                    slsConfig.getProject(),
                    node.getSlsLogstore(),
                    (int) fromTime,
                    (int) toTime,
                    "",
                    query,
                    0,
                    slsConfig.getQuery().getMaxResults(),
                    false
            );

            // 3. 执行查询（带超时和重试）
            GetLogsResponse response = executeQueryWithRetry(client, request, node.getName());

            // 4. 转换结果
            List<LogEntry> logs = convertLogs(response);

            NodeQueryResult result = NodeQueryResult.success(node.getName(), logs);
            result.setSlsLogstore(node.getSlsLogstore());
            return result;

        } catch (Exception e) {
            log.error("SLS查询失败: project={}, logstore={}, query={}",
                    slsConfig.getProject(), node.getSlsLogstore(), node.getQueryTemplate(), e);
            NodeQueryResult result = NodeQueryResult.failed(node.getName(), "SLS查询失败: " + e.getMessage());
            result.setSlsLogstore(node.getSlsLogstore());
            return result;
        }
    }

    /**
     * 执行查询（带重试）
     */
    private GetLogsResponse executeQueryWithRetry(
            Client client,
            GetLogsRequest request,
            String nodeName) throws Exception {

        int retryTimes = slsConfig.getQuery().getRetryTimes();
        Exception lastException = null;

        for (int i = 0; i <= retryTimes; i++) {
            try {
                return client.GetLogs(request);
            } catch (LogException e) {
                lastException = e;
                log.warn("节点[{}]查询失败，重试 {}/{}: {}", nodeName, i + 1, retryTimes, e.getMessage());
                if (i < retryTimes) {
                    Thread.sleep(1000 * (i + 1)); // 递增延迟
                }
            }
        }

        throw lastException != null ? lastException : new Exception("查询失败");
    }

    /**
     * 直接查询指定logstore的日志
     *
     * @param logstore logstore名称
     * @param query 查询语句
     * @param fromTime 开始时间（Unix时间戳，秒）
     * @param toTime 结束时间（Unix时间戳，秒）
     * @param offset 查询偏移量（用于分页）
     * @param limit 查询限制数量
     * @return 日志条目列表
     */
    public List<LogEntry> queryLogstore(
            String logstore,
            String query,
            long fromTime,
            long toTime,
            Integer offset,
            Integer limit) {
        return queryLogstore(slsConfig.getProject(), logstore, query, fromTime, toTime, offset, limit);
    }

    /**
     * 查询指定 project 的 logstore（AI 排查工具多环境查询用）
     */
    public List<LogEntry> queryLogstore(
            String project,
            String logstore,
            String query,
            long fromTime,
            long toTime,
            Integer offset,
            Integer limit) {

        try {
            // 验证时间戳
            if (fromTime < 0 || toTime < 0) {
                log.error("时间戳不能为负数: fromTime={}, toTime={}", fromTime, toTime);
                throw new IllegalArgumentException("时间戳不能为负数");
            }
            if (fromTime > Integer.MAX_VALUE || toTime > Integer.MAX_VALUE) {
                log.error("时间戳超出int范围，可能传递了毫秒级时间戳: fromTime={}, toTime={}", fromTime, toTime);
                throw new IllegalArgumentException("时间戳应为秒级，不应超过int最大值");
            }

            // 调用SLS API
            Client client = slsClientFactory.getClient();
            GetLogsRequest request = new GetLogsRequest(
                    project,
                    logstore,
                    (int) fromTime,
                    (int) toTime,
                    "",
                    query,
                    offset != null ? offset : 0,
                    limit != null ? limit : slsConfig.getQuery().getMaxResults(),
                    false
            );

            // 执行查询（带超时和重试）
            GetLogsResponse response = executeQueryWithRetry(client, request, logstore);

            // 转换结果
            return convertLogs(response);

        } catch (Exception e) {
            log.error("SLS查询失败: project={}, logstore={}, query={}",
                    project, logstore, query, e);
            throw new RuntimeException("SLS查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行 SLS SQL 分析查询（带 | SELECT 的聚合查询）
     * 返回每行结果 as Map<String, String>
     */
    public List<Map<String, String>> queryAnalytics(
            String logstore,
            String query,
            long fromTime,
            long toTime,
            int limit) {
        return queryAnalytics(slsConfig.getProject(), logstore, query, fromTime, toTime, limit);
    }

    /**
     * 指定 project 的 SQL 分析查询（AI 排查工具多环境查询用）
     */
    public List<Map<String, String>> queryAnalytics(
            String project,
            String logstore,
            String query,
            long fromTime,
            long toTime,
            int limit) {
        return queryAnalytics(project, logstore, query, fromTime, toTime, 0, limit);
    }

    /**
     * SQL 分析查询（带 offset 分页）
     */
    public List<Map<String, String>> queryAnalytics(
            String project,
            String logstore,
            String query,
            long fromTime,
            long toTime,
            int offset,
            int limit) {

        List<Map<String, String>> results = new ArrayList<>();
        try {
            Client client = slsClientFactory.getClient();
            GetLogsRequest request = new GetLogsRequest(
                    project,
                    logstore,
                    (int) fromTime,
                    (int) toTime,
                    "",
                    query,
                    offset,
                    limit,
                    false
            );

            GetLogsResponse response = executeQueryWithRetry(client, request, logstore);
            if (response != null && response.GetLogs() != null) {
                for (var queriedLog : response.GetLogs()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    queriedLog.mLogItem.GetLogContents().forEach(content ->
                            row.put(content.GetKey(), content.GetValue()));
                    results.add(row);
                }
            }
        } catch (Exception e) {
            log.error("SLS分析查询失败: project={}, logstore={}, query={}",
                    project, logstore, query, e);
            throw new RuntimeException("SLS分析查询失败: " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * 分页执行 SQL 分析查询，避免 GROUP BY 结果超过单页 limit 被静默截断
     *
     * @param pageSize 每页行数
     * @param maxRows 最大拉取行数上限
     */
    public List<Map<String, String>> queryAnalyticsPaged(
            String logstore,
            String query,
            long fromTime,
            long toTime,
            int pageSize,
            int maxRows) {
        List<Map<String, String>> all = new ArrayList<>();
        int offset = 0;
        while (offset < maxRows) {
            int line = Math.min(pageSize, maxRows - offset);
            List<Map<String, String>> page = queryAnalytics(slsConfig.getProject(), logstore, query, fromTime, toTime, offset, line);
            all.addAll(page);
            if (page.size() < line) {
                break;
            }
            offset += line;
        }
        return all;
    }

    /**
     * 获取查询条件下的日志总数
     *
     * @param logstore logstore名称
     * @param query 查询语句
     * @param fromTime 开始时间（Unix时间戳，秒）
     * @param toTime 结束时间（Unix时间戳，秒）
     * @return 日志总数
     */
    public long countLogstore(
            String logstore,
            String query,
            long fromTime,
            long toTime) {
        return countLogstore(slsConfig.getProject(), logstore, query, fromTime, toTime);
    }

    /**
     * 指定 project 的日志总数统计（AI 排查工具多环境查询用）
     */
    public long countLogstore(
            String project,
            String logstore,
            String query,
            long fromTime,
            long toTime) {

        try {
            // 验证时间戳
            if (fromTime < 0 || toTime < 0) {
                log.error("时间戳不能为负数: fromTime={}, toTime={}", fromTime, toTime);
                throw new IllegalArgumentException("时间戳不能为负数");
            }
            if (fromTime > Integer.MAX_VALUE || toTime > Integer.MAX_VALUE) {
                log.error("时间戳超出int范围，可能传递了毫秒级时间戳: fromTime={}, toTime={}", fromTime, toTime);
                throw new IllegalArgumentException("时间戳应为秒级，不应超过int最大值");
            }

            // 调用SLS GetHistograms API获取总数
            Client client = slsClientFactory.getClient();

            // 执行查询（带超时和重试）
            GetHistogramsResponse response = executeCountQueryWithRetry(
                    client,
                    project,
                    logstore,
                    (int) fromTime,
                    (int) toTime,
                    query,
                    logstore
            );

            // 计算总数（将所有时间段的count相加）
            if (response != null && response.GetHistograms() != null) {
                long total = 0;
                for (var histogram : response.GetHistograms()) {
                    total += histogram.mCount;
                }
                return total;
            }

            return 0;

        } catch (Exception e) {
            log.error("SLS count查询失败: project={}, logstore={}, query={}",
                    project, logstore, query, e);
            throw new RuntimeException("SLS count查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行count查询（带重试）
     */
    private GetHistogramsResponse executeCountQueryWithRetry(
            Client client,
            String project,
            String logstore,
            int fromTime,
            int toTime,
            String query,
            String logstoreName) throws Exception {

        int retryTimes = slsConfig.getQuery().getRetryTimes();
        Exception lastException = null;

        for (int i = 0; i <= retryTimes; i++) {
            try {
                // SLS GetHistograms方法签名：GetHistograms(String project, String logstore, int from, int to, String query, String topic)
                return client.GetHistograms(project, logstore, fromTime, toTime, query, "");
            } catch (LogException e) {
                lastException = e;
                log.warn("count查询失败，重试 {}/{}: {}", i + 1, retryTimes, e.getMessage());
                if (i < retryTimes) {
                    Thread.sleep(1000 * (i + 1)); // 递增延迟
                }
            }
        }

        throw lastException != null ? lastException : new Exception("count查询失败");
    }

    /**
     * 转换SLS日志为LogEntry
     */
    private List<LogEntry> convertLogs(GetLogsResponse response) {
        List<LogEntry> logs = new ArrayList<>();

        if (response == null || response.GetLogs() == null) {
            return logs;
        }

        // 转换日志并按时间戳排序（从早到晚）
        return response.GetLogs().stream()
                .sorted((log1, log2) -> {
                    // 使用 QueriedLog 的时间戳进行排序
                    int time1 = log1.mLogItem.mLogTime;
                    int time2 = log2.mLogItem.mLogTime;
                    return Integer.compare(time1, time2);
                })
                .map(log -> LogEntry.fromQueriedLog(log))
                .toList();
    }
}
