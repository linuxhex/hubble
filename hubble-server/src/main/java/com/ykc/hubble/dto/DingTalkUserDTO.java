package com.ykc.hubble.dto;

import lombok.Data;

/**
 * 钉钉用户信息DTO
 *
 * @author Cloud Eyes Team
 */
@Data
public class DingTalkUserDTO {

    /**
     * 昵称
     */
    private String nick;

    /**
     * 头像URL
     * 钉钉API可能返回 avatarUrl 或 avatar 字段
     */
    private String avatarUrl;
    
    /**
     * 手机号
     */
    private String mobile;
}

