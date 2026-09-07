package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.hubble.entity.AlertThresholdConfig;
import com.ykc.hubble.mapper.AlertThresholdConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 告警阈值配置服务：统一管理接口劣化/流量暴涨/红黄盘等阈值，替代硬编码。
 * 带内存缓存，避免每次告警判断都查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertThresholdService {

    private final AlertThresholdConfigMapper mapper;
    /** 内存缓存：key → value，启动时懒加载，更新时刷新 */
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    /** 列出所有阈值配置 */
    public List<AlertThresholdConfig> list() {
        return mapper.selectList(new LambdaQueryWrapper<AlertThresholdConfig>().orderByAsc(AlertThresholdConfig::getConfigKey));
    }

    /** 更新阈值 */
    public void update(String key, String value) {
        AlertThresholdConfig config = mapper.selectOne(new LambdaQueryWrapper<AlertThresholdConfig>()
                .eq(AlertThresholdConfig::getConfigKey, key));
        if (config == null) {
            config = new AlertThresholdConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            mapper.insert(config);
        } else {
            config.setConfigValue(value);
            mapper.updateById(config);
        }
        cache.put(key, value);
    }

    /** 读取字符串阈值，未配置返回默认值 */
    public String get(String key, String defaultValue) {
        String value = cache.get(key);
        if (value == null) {
            AlertThresholdConfig config = mapper.selectOne(new LambdaQueryWrapper<AlertThresholdConfig>()
                    .eq(AlertThresholdConfig::getConfigKey, key));
            value = config != null ? config.getConfigValue() : defaultValue;
            if (value != null) {
                cache.put(key, value);
            }
        }
        return value != null ? value : defaultValue;
    }

    /** 读取 double 阈值 */
    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.warn("阈值配置解析失败: key={}, value={}, 用默认值 {}", key, get(key, null), defaultValue);
            return defaultValue;
        }
    }

    /** 读取 int 阈值 */
    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.warn("阈值配置解析失败: key={}, value={}, 用默认值 {}", key, get(key, null), defaultValue);
            return defaultValue;
        }
    }
}
