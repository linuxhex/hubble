package com.ykc.hubble.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${parent-system.jwt.public-key:your-public-key}")
    private String publicKey;

    @Value("${parent-system.jwt.expiration:3600000}")
    private Long expiration = 3600000L; // 默认1小时

    /**
     * 验证Token并解析Claims
     *
     * @param token JWT Token
     * @return Claims对象，如果验证失败返回null
     */
    public Claims parseToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(publicKey.getBytes(StandardCharsets.UTF_8));
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("Token解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证Token是否有效
     *
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return false;
        }
        
        // 检查Token是否过期
        Date expiration = claims.getExpiration();
        if (expiration != null && expiration.before(new Date())) {
            log.warn("Token已过期");
            return false;
        }
        
        return true;
    }

    /**
     * 从Token中获取用户ID
     *
     * @param token JWT Token
     * @return 用户ID
     */
    public String getUserId(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("userId", String.class) : null;
    }

    /**
     * 从Token中获取用户名
     *
     * @param token JWT Token
     * @return 用户名
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.get("username", String.class) : null;
    }

    /**
     * 获取用户信息
     *
     * @param token JWT Token
     * @return 用户信息Map
     */
    public Map<String, Object> getUserInfo(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            throw new RuntimeException("Token解析失败");
        }
        
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", claims.get("userId", String.class));
        userInfo.put("username", claims.get("username", String.class));
        userInfo.put("displayName", claims.get("displayName", String.class));
        return userInfo;
    }

    /**
     * 生成JWT Token
     *
     * @param claims 用户信息
     * @return JWT Token
     */
    public String generateToken(Map<String, Object> claims) {
        SecretKey key = Keys.hmacShaKeyFor(publicKey.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }
}
