package com.ykc.hubble.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 页面数据缓存实体：存储页面查询结果，支持1天过期自动清理
 *
 * @author Hubble Team
 */
@Data
@TableName("page_data_cache")
public class PageDataCache {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 页面标识，如 'gateway_overview_1h'、'degradation_day'
     */
    @TableField("page_key")
    private String pageKey;

    /**
     * 数据标识，如 'overview'、'trend'、'hot_apis'
     */
    @TableField("data_key")
    private String dataKey;

    /**
     * JSON 格式的缓存数据
     */
    @TableField("data_content")
    private String dataContent;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 过期时间（创建时间 + 1天）
     */
    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
