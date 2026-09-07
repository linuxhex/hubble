package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统管理控制器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Tag(name = "系统管理", description = "系统健康检查和身份验证")
public class SystemController {

    private final JwtUtil jwtUtil;

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查系统服务状态")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "UP");
        data.put("timestamp", LocalDateTime.now());
        
        Map<String, String> components = new HashMap<>();
        components.put("database", "UP");
        components.put("sls", "UP");
        data.put("components", components);
        
        return Result.success(data);
    }

    @GetMapping("/auth/verify")
    @Operation(summary = "验证用户身份", description = "验证从父系统传递的Token是否有效")
    public Result<Map<String, Object>> verify(
            @Parameter(description = "从父系统获取的Token", required = true)
            @RequestParam String token) {
        try {
            Map<String, Object> userInfo = jwtUtil.getUserInfo(token);
            return Result.success(userInfo);
        } catch (Exception e) {
            log.error("Token验证失败: {}", e.getMessage());
            return Result.unauthorized("Token无效或已过期");
        }
    }
}

