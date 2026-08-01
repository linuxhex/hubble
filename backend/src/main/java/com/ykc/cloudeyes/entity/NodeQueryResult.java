package com.ykc.cloudeyes.entity;

import lombok.Data;

import java.util.List;

/**
 * 节点查询结果
 *
 * @author Cloud Eyes Team
 */
@Data
public class NodeQueryResult {

    /**
     * 节点ID
     */
    private Long nodeId;

    /**
     * 节点名称
     */
    private String nodeName;

    /**
     * 是否有子节点
     */
    private Boolean hasChildren;

    /**
     * 节点顺序
     */
    private Integer nodeOrder;

    /**
     * SLS Logstore名称
     */
    private String slsLogstore;

    /**
     * 查询状态
     */
    private String status; // success, failed, timeout

    /**
     * 日志条数
     */
    private Integer logCount;

    /**
     * 日志列表
     */
    private List<LogEntry> logs;

    /**
     * 错误信息（status为failed时）
     */
    private String error;

    /**
     * 创建成功结果
     */
    public static NodeQueryResult success(String nodeName, List<LogEntry> logs) {
        NodeQueryResult result = new NodeQueryResult();
        result.setNodeName(nodeName);
        result.setStatus("success");
        result.setLogCount(logs != null ? logs.size() : 0);
        result.setLogs(logs);
        return result;
    }

    /**
     * 创建失败结果
     */
    public static NodeQueryResult failed(String nodeName, String error) {
        NodeQueryResult result = new NodeQueryResult();
        result.setNodeName(nodeName);
        result.setStatus("failed");
        result.setLogCount(0);
        result.setLogs(List.of());
        result.setError(error);
        return result;
    }

    /**
     * 创建超时结果
     */
    public static NodeQueryResult timeout(String nodeName) {
        NodeQueryResult result = new NodeQueryResult();
        result.setNodeName(nodeName);
        result.setStatus("timeout");
        result.setLogCount(0);
        result.setLogs(List.of());
        result.setError("查询超时");
        return result;
    }
}
