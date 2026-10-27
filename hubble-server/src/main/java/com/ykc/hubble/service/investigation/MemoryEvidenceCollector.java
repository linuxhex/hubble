package com.ykc.hubble.service.investigation;

import com.ykc.hubble.entity.IncidentMemory;
import com.ykc.hubble.service.IncidentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 历史相似事故取证：按 alert_type×service 加权召回 Top3（借鉴 OpenSRE episodic memory）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEvidenceCollector implements EvidenceCollector {

    private final IncidentMemoryService incidentMemoryService;

    private static final int TOP_N = 3;

    @Override
    public String name() {
        return "历史相似事故";
    }

    @Override
    public Evidence collect(InvestigationContext ctx) {
        try {
            List<IncidentMemory> similar = incidentMemoryService.findSimilar(ctx.getAlertType(), ctx.getServiceName(), TOP_N);
            if (similar.isEmpty()) {
                return Evidence.degraded(name(), "无相似历史事故记录");
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("召回 %d 条相似历史事故：\n", similar.size()));
            int i = 1;
            for (IncidentMemory m : similar) {
                sb.append(String.format("%d. [%s/%s] %s（%s）%n   根因：%s%n   建议：%s%n",
                        i++, m.getAlertSource(), m.getAlertType(), m.getAlertName(),
                        m.getCreatedAt() == null ? "-" : m.getCreatedAt().toLocalDate(),
                        firstLine(m.getRootCause(), 200),
                        firstLine(m.getRecommendation(), 200)));
            }
            return Evidence.ok(name(), sb.toString());
        } catch (Exception e) {
            log.warn("历史事故召回失败: error={}", e.getMessage());
            return Evidence.degraded(name(), "历史事故查询失败: " + e.getMessage());
        }
    }

    private String firstLine(String s, int max) {
        if (s == null || s.isBlank()) return "-";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
