package com.ykc.cloudeyes.vo;

import lombok.Data;

import java.util.List;

/**
 * 节点查询结果VO
 *
 * @author Cloud Eyes Team
 */
@Data
public class NodeQueryResultVO {

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
    private List<LogEntryVO> logs;

    /**
     * 错误信息
     */
    private String error;
}
