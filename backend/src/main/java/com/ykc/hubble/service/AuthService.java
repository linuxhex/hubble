package com.ykc.hubble.service;

import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.config.DingtalkProperties;
import com.ykc.hubble.dto.DingTalkUserDTO;
import com.ykc.hubble.entity.User;
import com.ykc.hubble.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final DingTalkClient dingTalkClient;
    private final DingtalkProperties dingtalkProperties;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    /**
     * 钉钉登录
     *
     * @param code 授权码
     * @return 登录结果（包含token和用户信息）
     */
    public Map<String, Object> loginWithDingTalk(String code) {
        // 1. 获取accessToken
        String accessToken = dingTalkClient.getUserAccessToken(
                dingtalkProperties.getAppKey(),
                dingtalkProperties.getAppSecret(),
                code
        );

        if (!StringUtils.hasText(accessToken)) {
            throw new RuntimeException("获取钉钉accessToken失败");
        }

        // 2. 获取用户信息
        DingTalkUserDTO dingTalkUser = dingTalkClient.getUserInfoByAccessToken(accessToken);
        if (dingTalkUser == null) {
            throw new RuntimeException("获取钉钉用户信息失败");
        }

        // 3. 验证手机号
        String phone = dingTalkUser.getMobile();
        if (!StringUtils.hasText(phone)) {
            throw new RuntimeException("手机号不能为空");
        }

        // 4. 查找或创建用户
        User user = userService.findOrCreateByPhone(
                phone,
                dingTalkUser.getNick(),
                dingTalkUser.getAvatarUrl()  // 使用 getAvatarUrl() 方法，兼容两种字段名
        );

        // 5. 生成JWT Token
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId().toString());
        claims.put("username", user.getNickname());
        claims.put("phone", user.getPhone());
        String token = jwtUtil.generateToken(claims);

        // 6. 返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("user", Map.of(
                "id", user.getId(),
                "nickname", user.getNickname(),
                "avatar", user.getAvatar() != null ? user.getAvatar() : "",
                "phone", user.getPhone()
        ));

        return result;
    }
}

