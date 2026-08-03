package com.ykc.hubble.entity;

import cn.hutool.core.collection.CollectionUtil;
import com.aliyun.openservices.log.common.QueriedLog;

import java.util.Map;

/**
 * 日志条目
 * <p>
 * 注意：本类不使用 Lombok @Data（该类特定模式会触发 Lombok 字段处理异常，部分 setter 不生成），
 * 改为手写 getter/setter。
 *
 * @author Hubble Team
 */
public class LogEntry {

    private static final String LEVEL = "level";
    private static final String LINE = "line";
    private static final String MESSAGE = "message";
    private static final String MESSAGE_UNANALYZED = "__raw_log__";
    private static final String TRACE = "trace";
    private static final String TIME = "time";
    private static final String CONTAINER_IP = "__tag__:_container_ip_";
    private static final String CONTAINER_NAME = "__tag__:_container_name_";

    private String level;
    private String line;
    private String message;
    private String trace;
    private String time;
    private String containerIp;
    /**
     * 容器名（≈ 服务名），用于下钻按服务分组
     */
    private String containerName;
    /**
     * 其他字段
     */
    private Map<String, String> fields;

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getLine() {
        return line;
    }

    public void setLine(String line) {
        this.line = line;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTrace() {
        return trace;
    }

    public void setTrace(String trace) {
        this.trace = trace;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getContainerIp() {
        return containerIp;
    }

    public void setContainerIp(String containerIp) {
        this.containerIp = containerIp;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public void setFields(Map<String, String> fields) {
        this.fields = fields;
    }

    public static LogEntry fromQueriedLog(QueriedLog log) {
        if (log == null || log.mLogItem == null || CollectionUtil.isEmpty(log.mLogItem.GetLogContents())) {
            return null;
        }
        LogEntry logDTO = new LogEntry();
        log.mLogItem.GetLogContents().forEach(content -> {
            String key = content.GetKey();
            String value = content.GetValue();
            if (LEVEL.equals(key)) {
                logDTO.setLevel(value);
            } else if (LINE.equals(key)) {
                logDTO.setLine(value);
            } else if (MESSAGE_UNANALYZED.equals(key) || MESSAGE.equals(key)) {
                logDTO.setMessage(value);
            } else if (TRACE.equals(key)) {
                logDTO.setTrace(value);
            } else if (TIME.equals(key)) {
                logDTO.setTime(value);
            } else if (CONTAINER_IP.equals(key)) {
                logDTO.setContainerIp(value);
            } else if (CONTAINER_NAME.equals(key)) {
                logDTO.setContainerName(value);
            } else {
                if (logDTO.getFields() == null) {
                    logDTO.setFields(new java.util.HashMap<>());
                }
                logDTO.getFields().put(key, value);
            }
        });

        // 如果日志内容中没有 time 字段，使用 SLS 日志对象的标准时间戳
        if (logDTO.getTime() == null || logDTO.getTime().isEmpty()) {
            // mLogTime 是 Unix 时间戳（秒），转换为字符串
            logDTO.setTime(String.valueOf(log.mLogItem.mLogTime));
        }

        return logDTO;
    }
}
