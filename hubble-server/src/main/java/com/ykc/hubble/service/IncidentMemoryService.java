package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ykc.hubble.entity.IncidentMemory;
import com.ykc.hubble.mapper.IncidentMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 事故记忆服务：调查记录落库 + 相似历史事故召回（借鉴 OpenSRE episodic memory）
 * 召回权重：alert_type 匹配 0.5 + service 匹配 0.3 + resolved 0.2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentMemoryService {

    private final IncidentMemoryMapper incidentMemoryMapper;

    public IncidentMemory save(IncidentMemory record) {
        incidentMemoryMapper.insert(record);
        return record;
    }

    public void update(IncidentMemory record) {
        record.setUpdatedAt(LocalDateTime.now());
        incidentMemoryMapper.updateById(record);
    }

    public IncidentMemory getById(Long id) {
        return incidentMemoryMapper.selectById(id);
    }

    /** 30 分钟内同去重键是否已有记录（调查中/已完成均算，防重复调查） */
    public IncidentMemory findRecentByDedupKey(String dedupKey, int minutes) {
        return incidentMemoryMapper.selectRecentByDedupKey(dedupKey, LocalDateTime.now().minusMinutes(minutes));
    }

    public Page<IncidentMemory> page(int pageNo, int pageSize, String source, String status) {
        LambdaQueryWrapper<IncidentMemory> wrapper = new LambdaQueryWrapper<>();
        if (source != null && !source.isBlank()) {
            wrapper.eq(IncidentMemory::getAlertSource, source);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(IncidentMemory::getStatus, status);
        }
        wrapper.orderByDesc(IncidentMemory::getId);
        return incidentMemoryMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
    }

    /**
     * 召回相似历史事故（只取 DONE），按加权分排序取 Top N。
     * score = (alertType相等?0.5:0) + (service相等?0.3:0) + (resolved?0.2:0)
     */
    public List<IncidentMemory> findSimilar(String alertType, String serviceName, int topN) {
        List<IncidentMemory> candidates = new ArrayList<>();
        // 两者都匹配的优先少量取，再补单维度匹配，扩大召回面
        candidates.addAll(incidentMemoryMapper.selectSimilarBoth(alertType, serviceName, 5));
        candidates.addAll(incidentMemoryMapper.selectSimilarByType(alertType, 10));
        candidates.addAll(incidentMemoryMapper.selectSimilarByService(serviceName, 10));

        return candidates.stream()
                .distinct()
                .sorted((a, b) -> Double.compare(score(b, alertType, serviceName), score(a, alertType, serviceName)))
                .limit(topN)
                .toList();
    }

    private double score(IncidentMemory m, String alertType, String serviceName) {
        double s = 0;
        if (m.getAlertType() != null && m.getAlertType().equals(alertType)) s += 0.5;
        if (m.getServiceName() != null && m.getServiceName().equals(serviceName)) s += 0.3;
        if (m.getResolved() != null && m.getResolved() == 1) s += 0.2;
        return s;
    }

    /** 启动恢复：把超 minutes 分钟仍 INVESTIGATING 的记录置 FAILED（服务重启导致的悬挂调查） */
    public int failStaleInvestigations(int minutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutes);
        LambdaQueryWrapper<IncidentMemory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IncidentMemory::getStatus, "INVESTIGATING").lt(IncidentMemory::getCreatedAt, threshold);
        List<IncidentMemory> stale = incidentMemoryMapper.selectList(wrapper);
        for (IncidentMemory m : stale) {
            m.setStatus("FAILED");
            m.setErrorMessage("服务重启导致调查中断");
            update(m);
        }
        if (!stale.isEmpty()) {
            log.info("事故调查启动恢复: {} 条 INVESTIGATING 置为 FAILED", stale.size());
        }
        return stale.size();
    }
}
