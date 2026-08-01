package com.ykc.cloudeyes.controller;

import com.ykc.cloudeyes.common.Result;
import com.ykc.cloudeyes.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "认证服务", description = "用户认证相关接口")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/dingtalk/login")
    @Operation(summary = "钉钉登录", description = "通过钉钉授权码完成登录")
    public Result<Map<String, Object>> dingTalkLogin(
            @Parameter(description = "钉钉授权码", required = true)
            @RequestParam String code
    ) {
        if (!StringUtils.hasText(code)) {
            return Result.error(400, "授权码不能为空");
        }

        try {
            Map<String, Object> result = authService.loginWithDingTalk(code);
            return Result.success(result);
        } catch (Exception e) {
            log.error("钉钉登录失败: {}", e.getMessage(), e);
            return Result.error(500, "登录失败: " + e.getMessage());
        }
    }
}

