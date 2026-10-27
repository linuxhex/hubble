package com.ykc.hubble.service;

import com.ykc.hubble.entity.ServiceDependency;
import com.ykc.hubble.mapper.ServiceDependencyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 服务依赖图服务：依赖巡检扫描时沉淀邻接表，告警时反查上游调用方算爆炸半径。
 * 不引入 Neo4j，H2 邻接表按依赖巡检节奏滚动更新即可满足告警取证需要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceDependencyService {

    private final ServiceDependencyMapper serviceDependencyMapper;

    /** 巡检扫到一组依赖即沉淀（幂等 upsert：存在则只刷新 last_seen_at） */
    public void upsert(String appName, String dependsOn, String rpcType) {
        if (isBlank(appName) || isBlank(dependsOn)) {
            return;
        }
        String safeApp = appName.trim();
        String safeDep = dependsOn.trim();
        String safeType = rpcType == null || rpcType.isBlank() ? "-" : rpcType.trim();
        try {
            ServiceDependency existing = serviceDependencyMapper.selectByAppAndDepends(safeApp, safeDep, safeType);
            long now = System.currentTimeMillis();
            if (existing != null) {
                if (existing.getLastSeenAt() == null || now - existing.getLastSeenAt() > 3600_000L) {
                    // 一小时内不重复刷，减少无谓写放大
                    ServiceDependency update = new ServiceDependency();
                    update.setId(existing.getId());
                    update.setLastSeenAt(now);
                    serviceDependencyMapper.updateById(update);
                }
                return;
            }
            ServiceDependency dep = new ServiceDependency();
            dep.setAppName(safeApp);
            dep.setDependsOn(safeDep);
            dep.setRpcType(safeType);
            dep.setLastSeenAt(now);
            serviceDependencyMapper.insert(dep);
        } catch (Exception e) {
            // 依赖图是增强能力，写入失败不影响巡检主流程
            log.warn("服务依赖沉淀失败: app={}, dependsOn={}, error={}", safeApp, safeDep, e.getMessage());
        }
    }

    /** 反查上游：谁依赖了该服务（爆炸半径） */
    public List<ServiceDependency> findUpstream(String serviceName) {
        if (isBlank(serviceName)) {
            return List.of();
        }
        return serviceDependencyMapper.selectUpstream(serviceName.trim());
    }

    /** 依赖图是否已有该服务的数据（冷启动判断） */
    public boolean hasDataFor(String serviceName) {
        return !findUpstream(serviceName).isEmpty();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank() || "-".equals(s);
    }
}
