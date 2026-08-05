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
     * 不需要认证的路径
     */
    private static final String[] EXCLUDE_PATHS = {

            "/system/health",
            "/auth/verify",
            "/auth/dingtalk/login",
            "/swagger-ui",
            "/v3/api-docs",

            // 业务接口白名单
            "/traces/query",
            "/sls-keywords/query",

            // 监控大盘接口（无需用户认证）
            "/alert-config/query",
            "/alert-data/query",
            "/alert-data/service-health",
            "/alert-data/minute-timeline",
            "/alert-data/service-drilldown",
            "/alert-data/service-logs",
            "/alert-data/trace-logs",
            "/alert-data/sse",
            "/error-analysis/query",

            // 网关大盘和日志搜索接口
            "/gateway/"
    };

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        
        // 去掉 context-path 前缀，获取相对路径
        String relativePath = requestPath;
        if (contextPath != null && !contextPath.isEmpty() && requestPath.startsWith(contextPath)) {
            relativePath = requestPath.substring(contextPath.length());
        }
        
        // 检查是否为排除路径
        if (isExcludePath(relativePath)) {
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
