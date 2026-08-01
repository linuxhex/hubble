package com.ykc.cloudeyes.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.cloudeyes.entity.User;
import com.ykc.cloudeyes.mapper.UserMapper;
import com.ykc.cloudeyes.util.PinyinUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 用户服务
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final int MAX_NICKNAME_LENGTH = 32;

    /**
     * 根据手机号查找或创建用户
     *
     * @param phone 手机号
     * @param nickname 昵称
     * @param avatar 头像URL
     * @return 用户对象
     */
    @Transactional(rollbackFor = Exception.class)
    public User findOrCreateByPhone(String phone, String nickname, String avatar) {
        if (!StringUtils.hasText(phone)) {
            throw new IllegalArgumentException("手机号不能为空");
        }

        // 根据手机号查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhone, phone);
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            // 用户不存在，创建新用户
            user = new User();
            user.setPhone(phone);
            user.setNickname(processNickname(nickname));
            user.setAvatar(avatar);
            user.setLastLoginTime(LocalDateTime.now());
            userMapper.insert(user);
            log.info("创建新用户: phone={}, nickname={}", phone, user.getNickname());
        } else {
            // 用户已存在，更新信息
            if (StringUtils.hasText(avatar)) {
                user.setAvatar(avatar);
            }
            user.setLastLoginTime(LocalDateTime.now());
            userMapper.updateById(user);
            log.info("更新用户登录时间: phone={}", phone);
        }

        return user;
    }

    /**
     * 更新最后登录时间
     *
     * @param userId 用户ID
     */
    public void updateLastLoginTime(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 处理昵称：中文转拼音，限制长度
     *
     * @param nickname 原始昵称
     * @return 处理后的昵称
     */
    public String processNickname(String nickname) {
        if (!StringUtils.hasText(nickname)) {
            return "user";
        }

        // 判断是否包含中文
        if (CHINESE_PATTERN.matcher(nickname).find()) {
            // 包含中文，转换为拼音
            String pinyin = PinyinUtil.toPinyin(nickname);
            if (StringUtils.hasText(pinyin)) {
                return pinyin;
            }
        }

        // 不包含中文或转换失败，直接使用原昵称
        String processed = nickname.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (processed.length() > MAX_NICKNAME_LENGTH) {
            processed = processed.substring(0, MAX_NICKNAME_LENGTH);
        }
        if (!StringUtils.hasText(processed)) {
            processed = "user";
        }
        return processed;
    }
}

