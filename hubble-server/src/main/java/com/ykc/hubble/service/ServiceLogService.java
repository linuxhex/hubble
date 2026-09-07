package com.ykc.hubble.service;

import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.util.TimeRanges;
import com.ykc.hubble.vo.ServiceLogVO;
import com.ykc.hubble.vo.SlsKeywordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 下钻服务：从大盘异常项下钻到服务日志、再按 traceId 聚合跨服务日志。
 * <p>
 * 采用「SLS trace 轻量链路」：all 聚合库按 trace 字段精确捞同一请求穿过的服务日志，
 * 不依赖 ARMS（后端未接入）。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceLogService {

    private final AlertConfigService alertConfigService;
    private final SlsKeywordService slsKeywordService;
    private final SlsQueryClient slsQueryClient;
    private final MonitorProperties monitorProperties;

    /**
     * 下钻：监控项对应服务的实时日志
     */
    public List<ServiceLogVO> serviceLogs(Long configId, String timeRange, int limit) {
        AlertConfig cfg = alertConfigService.detail(configId);
        if (cfg == null) {
            log.warn("监控项不存在: configId={}", configId);
            return List.of();
        }
        SlsKeywordVO template = slsKeywordService.getSlsKeywordDetail(cfg.getKeywordTemplateId());
        String logstore = resolveLogstore(template);
        String application = template != null ? template.getApplication() : null;

        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        String query = (template != null && template.getKeywords() != null && !template.getKeywords().isBlank())
                ? template.getKeywords()
                : "*";

        try {
            List<LogEntry> logs = slsQueryClient.queryLogstore(logstore, query, from, now, 0, limit);
            return logs.stream().map(e -> toVO(e, application)).toList();
        } catch (Exception e) {
            log.warn("查询服务日志失败: configId={}, error={}", configId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 下钻：按 traceId 查同一请求穿过的跨服务日志（轻量链路）
     */
    public List<ServiceLogVO> traceLogs(String traceId, String timeRange, int limit) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis() / 1000;
        long from = now - TimeRanges.toSeconds(timeRange);
        // all 聚合库按 trace 字段精确捞同一 traceId 的日志，跨服务串联
        String query = "trace: " + traceId.trim();
        try {
            List<LogEntry> logs = slsQueryClient.queryLogstore(
                    monitorProperties.getDefaultQueryLogstore(), query, from, now, 0, limit);
            return logs.stream().map(e -> toVO(e, null)).toList();
        } catch (Exception e) {
            log.warn("查询链路日志失败: traceId={}, error={}", traceId, e.getMessage());
            return List.of();
        }
    }

    private String resolveLogstore(SlsKeywordVO template) {
        if (template != null && template.getLogstore() != null && !template.getLogstore().isBlank()) {
            return template.getLogstore();
        }
        return monitorProperties.getDefaultQueryLogstore();
    }

    private ServiceLogVO toVO(LogEntry log, String fallbackService) {
        ServiceLogVO vo = new ServiceLogVO();
        vo.setLevel(log.getLevel());
        vo.setMessage(log.getMessage());
        vo.setTrace(log.getTrace());
        // 容器名优先作为服务名；缺失时回退到模板 application
        vo.setService(log.getContainerName() != null ? log.getContainerName() : fallbackService);
        vo.setTime(parseTimeToMillis(log.getTime()));
        return vo;
    }

    private long parseTimeToMillis(String time) {
        if (time == null) {
            return 0L;
        }
        try {
            return Long.parseLong(time) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
