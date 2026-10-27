package com.ykc.hubble.service.investigation;

import com.ykc.hubble.entity.IncidentMemory;
import com.ykc.hubble.entity.ServiceDependency;
import com.ykc.hubble.service.IncidentMemoryService;
import com.ykc.hubble.service.ServiceDependencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 依赖图取证：反查上游调用方（爆炸半径）+ 上游最近 1 小时是否也有告警（连锁故障线索）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyEvidenceCollector implements EvidenceCollector {

    private final ServiceDependencyService serviceDependencyService;
    private final IncidentMemoryService incidentMemoryService;

    @Override
    public String name() {
        return "依赖图爆炸半径";
    }

    @Override
    public Evidence collect(InvestigationContext ctx) {
        String service = ctx.getServiceName();
        if (service == null || service.isBlank()) {
            return Evidence.degraded(name(), "无服务名，无法分析依赖");
        }
        try {
            List<ServiceDependency> upstream = serviceDependencyService.findUpstream(service);
            if (upstream.isEmpty()) {
                return Evidence.degraded(name(), "依赖图数据不足（该服务暂无上游记录，依赖巡检数据积累后可用）");
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("服务 %s 的上游调用方 %d 个（爆炸半径）：\n", service, upstream.size()));
            for (ServiceDependency dep : upstream) {
                // 上游近 1 小时是否也有告警 → 连锁故障线索
                boolean upstreamAlerted = hasRecentAlert(dep.getAppName());
                sb.append(String.format("  %s (%s)%s%n", dep.getAppName(), dep.getRpcType(),
                        upstreamAlerted ? " ← 该上游近1小时也有告警，疑似连锁影响" : ""));
            }
            return Evidence.ok(name(), sb.toString());
        } catch (Exception e) {
            log.warn("依赖图取证失败: service={}, error={}", service, e.getMessage());
            return Evidence.degraded(name(), "依赖图查询失败: " + e.getMessage());
        }
    }

    private boolean hasRecentAlert(String appName) {
        try {
            List<IncidentMemory> similar = incidentMemoryService.findSimilar("-", appName, 3);
            return similar.stream().anyMatch(m ->
                    m.getCreatedAt() != null
                            && m.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusHours(1)));
        } catch (Exception e) {
            return false;
        }
    }
}
