package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ykc.hubble.common.exception.BusinessException;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.mapper.AlertConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志监控配置服务：监控项的增删改查、启停，以及对齐前端的列表视图。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertConfigService {

    private final AlertConfigMapper alertConfigMapper;
    private final SnapshotCache snapshotCache;
    private final DingtalkRobotService dingtalkRobotService;

    /**
     * 分页查询监控项（支持标题模糊 + 启用状态过滤）
     */
    public Map<String, Object> page(int current, int size, String title, Boolean enabled) {
        LambdaQueryWrapper<AlertConfig> wrapper = new LambdaQueryWrapper<AlertConfig>()
                .like(title != null && !title.isBlank(), AlertConfig::getTitle, title)
                .eq(enabled != null, AlertConfig::getEnabled, enabled)
                .orderByDesc(AlertConfig::getCreatedAt);
        Page<AlertConfig> page = alertConfigMapper.selectPage(new Page<>(current, size), wrapper);

        List<Map<String, Object>> records = new ArrayList<>();
        for (AlertConfig cfg : page.getRecords()) {
            records.add(toView(cfg));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", page.getTotal());
        return result;
    }

    /**
     * 详情
     */
    public AlertConfig detail(Long id) {
        AlertConfig cfg = alertConfigMapper.selectById(id);
        if (cfg == null) {
            throw new BusinessException(404, "监控配置不存在");
        }
        // 回填绑定的机器人 ID 列表
        cfg.setRobotIds(dingtalkRobotService.listRobotIdsByConfigId(id));
        return cfg;
    }

    /**
     * 创建监控项
     */
    public Long create(AlertConfig cfg) {
        fillDefaults(cfg);
        cfg.setId(null);
        cfg.setDeleted(0);
        alertConfigMapper.insert(cfg);
        // 保存机器人绑定
        if (cfg.getRobotIds() != null) {
            dingtalkRobotService.saveBindings(cfg.getId(), cfg.getRobotIds());
        }
        log.info("创建监控配置: id={}, title={}", cfg.getId(), cfg.getTitle());
        return cfg.getId();
    }

    /**
     * 更新监控项
     */
    public void update(Long id, AlertConfig cfg) {
        AlertConfig existing = alertConfigMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "监控配置不存在");
        }
        cfg.setId(id);
        alertConfigMapper.updateById(cfg);
        // 更新机器人绑定
        if (cfg.getRobotIds() != null) {
            dingtalkRobotService.saveBindings(id, cfg.getRobotIds());
        }
    }

    /**
     * 删除监控项（逻辑删除，并清理其快照）
     */
    public void delete(Long id) {
        AlertConfig existing = alertConfigMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "监控配置不存在");
        }
        alertConfigMapper.deleteById(id);
        snapshotCache.evict(id);
    }

    /**
     * 启用 / 禁用
     */
    public void setEnabled(Long id, boolean enabled) {
        AlertConfig existing = alertConfigMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "监控配置不存在");
        }
        existing.setEnabled(enabled);
        alertConfigMapper.updateById(existing);
        // 禁用时清理快照，避免大盘继续展示过期数据
        if (!enabled) {
            snapshotCache.evict(id);
        }
    }

    /**
     * 查所有启用的监控项（供采集调度使用）
     */
    public List<AlertConfig> listEnabled() {
        return alertConfigMapper.selectList(new LambdaQueryWrapper<AlertConfig>()
                .eq(AlertConfig::getEnabled, true));
    }

    /**
     * 填充默认值，保证入库字段完整
     */
    private void fillDefaults(AlertConfig cfg) {
        if (cfg.getEnabled() == null) {
            cfg.setEnabled(true);
        }
        if (cfg.getStartTime() == null || cfg.getStartTime().isBlank()) {
            cfg.setStartTime("00:00:00");
        }
        if (cfg.getEndTime() == null || cfg.getEndTime().isBlank()) {
            cfg.setEndTime("23:59:59");
        }
        if (cfg.getCollectionInterval() == null || cfg.getCollectionInterval() <= 0) {
            cfg.setCollectionInterval(60);
        }
        if (cfg.getAlertThreshold() == null || cfg.getAlertThreshold() <= 0) {
            cfg.setAlertThreshold(50);
        }
    }

    /**
     * 转前端视图：补充采集间隔展示串（如 "60秒"/"5分钟"）
     */
    private Map<String, Object> toView(AlertConfig cfg) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", cfg.getId());
        view.put("title", cfg.getTitle());
        view.put("description", cfg.getDescription());
        view.put("keywordTemplateId", cfg.getKeywordTemplateId());
        view.put("startTime", cfg.getStartTime());
        view.put("endTime", cfg.getEndTime());
        view.put("collectionInterval", cfg.getCollectionInterval());
        view.put("collectionIntervalDisplay", intervalDisplay(cfg.getCollectionInterval()));
        view.put("alertThreshold", cfg.getAlertThreshold());
        view.put("alertWebhook", cfg.getAlertWebhook());
        view.put("enabled", cfg.getEnabled());
        return view;
    }

    private String intervalDisplay(Integer seconds) {
        if (seconds == null) {
            return "";
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            return (seconds / 60) + "分钟";
        }
        return seconds + "秒";
    }
}
