package com.ykc.cloudeyes.common.exception;

import lombok.Getter;

/**
 * SLS查询异常
 *
 * @author Cloud Eyes Team
 */
@Getter
public class SlsException extends BusinessException {

    /**
     * 错误类型
     */
    private final SlsErrorType errorType;

    public SlsException(SlsErrorType errorType, String message) {
        super(500, message);
        this.errorType = errorType;
    }

    public SlsException(SlsErrorType errorType, String message, Throwable cause) {
        super(500, message, cause);
        this.errorType = errorType;
    }

    /**
     * SLS错误类型枚举
     */
    public enum SlsErrorType {
        /**
         * 连接失败
         */
        CONNECTION_FAILED,
        /**
         * 认证失败
         */
        AUTHENTICATION_FAILED,
        /**
         * 权限不足
         */
        PERMISSION_DENIED,
        /**
         * 项目不存在
         */
        PROJECT_NOT_FOUND,
        /**
         * 日志库不存在
         */
        LOGSTORE_NOT_FOUND,
        /**
         * 查询语法错误
         */
        QUERY_SYNTAX_ERROR,
        /**
         * 查询超时
         */
        QUERY_TIMEOUT,
        /**
         * 系统错误
         */
        SYSTEM,
        /**
         * 其他错误
         */
        OTHER
    }
}

