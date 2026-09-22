package com.ykc.hubble.config;

import com.ykc.hubble.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 身份验证过滤器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    /**
     * 白名单前缀内仍要求认证的敏感路径（精确匹配，优先级高于白名单）
     */
    private static final String[] PROTECTED_PATHS = {
            // /gateway/ 前缀下的日志内容读取接口（SLS 日志）
            "/gateway/logs/query"
    };

    /**
     * 不需要认证的路径
     */
    private static final String[] EXCLUDE_PATHS = {

            "/system/health",
            "/auth/verify",
            "/auth/dingtalk/login",
            "/swagger-ui",
            "/v3/api-docs",

            // 监控大盘接口（无需用户认证）
            "/alert-data/query",
            "/alert-data/service-health",
            "/alert-data/minute-timeline",
            "/alert-data/service-drilldown",
            "/alert-data/service-logs",
            "/alert-data/trace-logs",
            "/alert-data/sse",
            "/error-analysis/query",

            // 网关大盘和日志搜索接口
            "/gateway/",

            // 中间件监控接口（无需用户认证）
            "/middleware/",

            // 中间件告警接口（无需用户认证）
            "/middleware-alert/",

            // 服务负载接口（无需用户认证）
            "/service-load/",

            // AI 对话 WebSocket（握手阶段放行，onOpen 内校验 JWT）
            "/ws/ai/chat"
    };

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        String requestPath = request.getRequestURI();
        
        // 只拦截 /api/ 路径，其他路径（静态资源、前端路由）直接放行
        if (!requestPath.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 去掉 /api 前缀，获取 Controller 相对路径
        String relativePath = requestPath.substring(4);
        
        // 检查是否为排除路径（白名单内的敏感路径仍要求认证）
        if (!isProtectedPath(relativePath) && isExcludePath(relativePath)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 从请求头或URL参数获取Token
        String token = getTokenFromRequest(request);
        
        if (!StringUtils.hasText(token)) {
            log.warn("请求缺少Token: {}", requestPath);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未授权，请先登录\"}");
            return;
        }
        
        // 验证Token
        if (!jwtUtil.validateToken(token)) {
            log.warn("Token验证失败: {}", requestPath);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Token无效或已过期\"}");
            return;
        }
        
        // Token验证通过，继续请求
        filterChain.doFilter(request, response);
    }

    /**
     * 从请求中获取Token
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        // 1. 从Authorization头获取
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // 2. 从URL参数获取
        String tokenParam = request.getParameter("token");
        if (StringUtils.hasText(tokenParam)) {
            return tokenParam;
        }
        
        return null;
    }

    /**
     * 检查是否为白名单内的敏感路径
     */
    private boolean isProtectedPath(String path) {
        for (String protectedPath : PROTECTED_PATHS) {
            if (path.equals(protectedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为排除路径
     */
    private boolean isExcludePath(String path) {
        for (String excludePath : EXCLUDE_PATHS) {
            if (path.startsWith(excludePath)) {
                return true;
            }
        }
        return false;
    }
}
