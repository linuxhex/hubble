package com.ykc.cloudeyes.entity;

import cn.hutool.core.collection.CollectionUtil;
import com.aliyun.openservices.log.common.QueriedLog;
import lombok.Data;

import java.util.Map;

/**
 * 日志条目
 *
 * @author Cloud Eyes Team
 */
@Data
public class LogEntry {

    private final static String LEVEL = "level";
    private final static String LINE = "line";
    private final static String MESSAGE = "message";
    private final static String MESSAGE_UNANALYZED = "__raw_log__";
    private final static String TRACE = "trace";
    private final static String TIME = "time";
    private final static String CONTAINER_IP = "__tag__:_container_ip_";

    private String level;
    private String line;
    private String message;
    private String trace;
    private String time;
    private String containerIp;
    /**
     * 其他字段
     */
    private Map<String, String> fields;

    public static LogEntry fromQueriedLog(QueriedLog log) {
        if (log == null || log.mLogItem == null || CollectionUtil.isEmpty(log.mLogItem.GetLogContents())) {
            return null;
        }
        LogEntry logDTO = new LogEntry();
        log.mLogItem.GetLogContents().forEach(content -> {
            switch (content.GetKey()) {
                case LEVEL -> logDTO.setLevel(content.GetValue());
                case LINE -> logDTO.setLine(content.GetValue());
                case MESSAGE_UNANALYZED -> logDTO.setMessage(content.GetValue());
                case MESSAGE -> logDTO.setMessage(content.GetValue());
                case TRACE -> logDTO.setTrace(content.GetValue());
                case TIME -> logDTO.setTime(content.GetValue());
                case CONTAINER_IP -> logDTO.setContainerIp(content.GetValue());
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
