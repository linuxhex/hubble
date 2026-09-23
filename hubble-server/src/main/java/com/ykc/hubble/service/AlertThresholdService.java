package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.hubble.entity.AlertThresholdConfig;
import com.ykc.hubble.mapper.AlertThresholdConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 告警阈值配置服务：统一管理接口劣化/流量暴涨/红黄盘等阈值，替代硬编码。
 * 带内存缓存，避免每次告警判断都查库。
 * 启动时将默认阈值幂等初始化到 alert_threshold_config 表（已存在的 key 不覆盖），保证告警配置页可见可改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertThresholdService {

    private final AlertThresholdConfigMapper mapper;
    /** 内存缓存：key → value，启动时懒加载，更新时刷新 */
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    /** 默认阈值定义：key / 默认值 / 中文说明 */
    private record ThresholdDef(String key, String value, String description) {}

    /**
     * 全部默认阈值清单。新增阈值 key 时必须同步登记，否则告警配置页看不到该项。
     */
    private static final List<ThresholdDef> DEFAULT_THRESHOLDS = List.of(
            // ===== 异常大盘：动态基线（均值+标准差） =====
            new ThresholdDef("dynamic_red_sigma", "3", "异常大盘动态基线红盘：偏离均值倍数(标准差σ)"),
            new ThresholdDef("dynamic_yellow_sigma", "2", "异常大盘动态基线黄盘：偏离均值倍数(标准差σ)"),
            new ThresholdDef("dynamic_red_mean_mult", "5", "异常大盘动态基线红盘：达到均值的倍数"),
            new ThresholdDef("dynamic_yellow_mean_mult", "3", "异常大盘动态基线黄盘：达到均值的倍数"),
            new ThresholdDef("dynamic_red_floor", "10", "异常大盘动态基线红盘绝对下限(条/分钟)"),
            new ThresholdDef("dynamic_yellow_floor", "5", "异常大盘动态基线黄盘绝对下限(条/分钟)"),
            // ===== 异常大盘：分钟环比 =====
            new ThresholdDef("minute_red_multiplier", "6", "异常大盘分钟环比红盘：较前一分钟增长倍数"),
            new ThresholdDef("minute_yellow_multiplier", "3", "异常大盘分钟环比黄盘：较前一分钟增长倍数"),
            new ThresholdDef("minute_red_floor", "5", "异常大盘分钟环比红盘绝对下限(条/分钟)"),
            new ThresholdDef("minute_yellow_floor", "2", "异常大盘分钟环比黄盘绝对下限(条/分钟)"),
            // ===== 红盘防抖/恢复/冷却 =====
            new ThresholdDef("consecutive_red_count", "3", "连续命中红盘次数达到该值才发钉钉告警(防抖)"),
            new ThresholdDef("consecutive_recover_count", "3", "连续正常次数达到该值才解除告警状态"),
            new ThresholdDef("alert_cooldown_hours", "24", "同一对象告警冷却时长(小时)，冷却期内不重复告警"),
            // ===== 接口劣化 =====
            new ThresholdDef("degradation_threshold", "220", "接口劣化告警：P60 RT 达到基线的百分比(220=2.2倍)"),
            new ThresholdDef("degradation_min_rt", "1000", "接口劣化告警：当前 P60 RT 最低门槛(ms)，低于不告警"),
            new ThresholdDef("degradation_min_change_rate", "20", "接口劣化上榜门槛：RT 变化率最低百分比(%)"),
            new ThresholdDef("min_request_count", "10", "接口劣化/流量暴涨检测的最小请求数样本"),
            // ===== 流量暴涨 =====
            new ThresholdDef("traffic_surge_threshold", "200", "流量暴涨告警：流量达到基线的百分比(200=2倍)"),
            new ThresholdDef("traffic_surge_min_rate", "50", "流量暴涨上榜门槛：同比涨幅最低百分比(%)，默认对比昨天同时段"),
            new ThresholdDef("traffic_surge_min_qps", "50", "流量暴涨告警：当前 QPS 最低门槛，低于不告警"),
            // ===== 依赖服务 RT 同比巡检 =====
            new ThresholdDef("dependency_rt_alert_enabled", "1", "依赖服务 RT 同比巡检开关(1=开启 0=关闭)"),
            new ThresholdDef("dependency_rt_surge_ratio", "100", "依赖服务RT同比涨幅阈值(%)，今天当前5分钟均值对比昨天同一5分钟均值"),
            new ThresholdDef("dependency_rt_abs_floor_ms", "1500", "依赖服务RT全局绝对下限(ms)，当前RT低于此值不告警，过滤ms级噪声"),
            new ThresholdDef("dependency_rt_floor_ms", "1000", "依赖服务RT低流量绝对下限(ms)，仅对未达高流量门槛的组合生效"),
            new ThresholdDef("dependency_rt_min_count", "3000", "依赖服务高流量门槛(次/最近1分钟)，达到后免低流量RT下限，但仍受全局绝对下限约束"),
            new ThresholdDef("dependency_rt_min_delta_ms", "1", "依赖服务高流量组合的RT净增下限(ms)，过滤亚毫秒级指标(如Kafka)的相对涨幅噪声"),
            new ThresholdDef("dependency_rt_alert_cooldown_minutes", "180", "依赖服务告警冷却时长(分钟)"),
            // ===== 中间件告警：同比黄盘双条件 =====
            new ThresholdDef("mw_yoy_surge_threshold", "300", "中间件黄盘同比涨幅阈值(%)，相对昨天同时段"),
            new ThresholdDef("mw_yoy_abs_floor", "30", "中间件黄盘同比绝对值下限，当前值低于此不告警"),
            // ===== 服务负载/扩容评估 =====
            new ThresholdDef("service_load_cpu_yellow", "80", "服务负载 CPU 黄盘阈值(%)"),
            new ThresholdDef("service_load_cpu_red", "90", "服务负载 CPU 红盘阈值(%)"),
            new ThresholdDef("service_load_mem_yellow", "85", "服务负载内存黄盘阈值(%)"),
            new ThresholdDef("service_load_mem_red", "95", "服务负载内存红盘阈值(%)"),
            // ===== 其他 =====
            new ThresholdDef("gateway_chain_length", "20", "网关调用链展示长度上限(层)")
    );

    /**
     * 启动时幂等初始化默认阈值：库中缺失的 key 插入默认值+说明，已存在的不覆盖（保留运维改过的值）；
     * 同时预热内存缓存（优先用库中值）。
     */
    @PostConstruct
    public void seedDefaults() {
        try {
            List<AlertThresholdConfig> existing = mapper.selectList(null);
            Map<String, String> existingMap = new java.util.HashMap<>();
            for (AlertThresholdConfig c : existing) {
                existingMap.put(c.getConfigKey(), c.getConfigValue());
            }

            List<AlertThresholdConfig> toInsert = new ArrayList<>();
            for (ThresholdDef def : DEFAULT_THRESHOLDS) {
                String value = existingMap.get(def.key());
                if (value == null) {
                    AlertThresholdConfig config = new AlertThresholdConfig();
                    config.setConfigKey(def.key());
                    config.setConfigValue(def.value());
                    config.setDescription(def.description());
                    toInsert.add(config);
                    cache.put(def.key(), def.value());
                } else {
                    cache.put(def.key(), value);
                }
            }
            // 同步说明文案（始终与代码保持一致，确保描述准确）
            int descUpdated = 0;
            for (AlertThresholdConfig config : existing) {
                String expectedDesc = DEFAULT_THRESHOLDS.stream()
                        .filter(d -> d.key().equals(config.getConfigKey()))
                        .map(ThresholdDef::description)
                        .findFirst()
                        .orElse(null);
                if (expectedDesc != null && !expectedDesc.equals(config.getDescription())) {
                    config.setDescription(expectedDesc);
                    mapper.updateById(config);
                    descUpdated++;
                }
            }
            if (!toInsert.isEmpty() || descUpdated > 0) {
                for (AlertThresholdConfig config : toInsert) {
                    mapper.insert(config);
                }
                log.info("告警阈值初始化完成: 新增 {} 项默认阈值, 同步 {} 项说明文案, 已存在 {} 项保留原值",
                        toInsert.size(), descUpdated, DEFAULT_THRESHOLDS.size() - toInsert.size());
            } else {
                log.info("告警阈值初始化检查完成: {} 项阈值全部已存在，缓存已预热", DEFAULT_THRESHOLDS.size());
            }
        } catch (Exception e) {
            log.error("告警阈值初始化失败，将退化为代码内置默认值: {}", e.getMessage(), e);
        }
    }

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
            config.setUpdatedAt(java.time.LocalDateTime.now());
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
