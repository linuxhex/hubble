package com.ykc.hubble.common.exception;

import com.ykc.hubble.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.badRequest(message);
    }

    /**
     * 处理参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数绑定失败: {}", message);
        return Result.badRequest(message);
    }

    /**
     * 处理约束校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        log.warn("约束校验失败: {}", message);
        return Result.badRequest(message);
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理SLS异常
     */
    @ExceptionHandler(com.ykc.hubble.common.exception.SlsException.class)
    public Result<?> handleSlsException(com.ykc.hubble.common.exception.SlsException e) {
        log.error("SLS查询异常: type={}, message={}", e.getErrorType(), e.getMessage(), e);
        
        // 根据错误类型返回友好的错误信息
        String userMessage = switch (e.getErrorType()) {
            case CONNECTION_FAILED -> "无法连接到SLS服务，请检查网络连接";
            case AUTHENTICATION_FAILED -> "SLS认证失败，请检查AccessKey配置";
            case PERMISSION_DENIED -> "没有权限访问该SLS资源";
            case PROJECT_NOT_FOUND -> "SLS项目不存在";
            case LOGSTORE_NOT_FOUND -> "SLS日志库不存在";
            case QUERY_SYNTAX_ERROR -> "查询语法错误，请检查查询模板";
            case QUERY_TIMEOUT -> "查询超时，请稍后重试或缩小查询时间范围";
            default -> e.getMessage();
        };
        
        return Result.error(500, userMessage);
    }

    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常: type={}, message={}", e.getClass().getName(), e.getMessage(), e);
        // 开发环境返回更详细的错误信息
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = e.getClass().getSimpleName();
        }
        return Result.error("系统内部错误: " + detail);
    }
}

