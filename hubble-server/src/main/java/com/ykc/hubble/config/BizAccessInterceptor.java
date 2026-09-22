package com.ykc.hubble.config;

import com.ykc.hubble.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务监控数据访问拦截：biz-analysis 为经营敏感数据，前后端双重校验
 * （前端菜单按昵称隐藏，此处兜底接口层，防止绕过 UI 直接调接口）。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BizAccessInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    /** 允许访问业务监控的用户名，逗号分隔，与前端菜单可见性口径保持一致 */
    @Value("${biz-analysis.allowed-users:lianzi}")
    private String allowedUsers;

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler
    ) throws Exception {
        String token = getTokenFromRequest(request);
        String username = StringUtils.hasText(token) ? jwtUtil.getUsername(token) : null;

        Set<String> allowed = Arrays.stream(allowedUsers.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        if (username == null || !allowed.contains(username)) {
            log.warn("业务监控越权访问拦截: username={}, path={}", username, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"无权限访问业务监控数据\"}");
            return false;
        }
        return true;
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return request.getParameter("token");
    }
}
