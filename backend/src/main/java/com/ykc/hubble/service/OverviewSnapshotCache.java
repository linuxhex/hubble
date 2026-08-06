package com.ykc.hubble.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.vo.OverviewSnapshotPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 概览数据时序快照存储：按时间范围维护最近一段时间的采集点，
 * 供概览接口毫秒级读取，避免每次都打 SLS。
 *
 * @author Cloud Eyes Team
 */
@Service
@RequiredArgsConstructor
public class OverviewSnapshotCache {

    private final Cache<String, Deque<OverviewSnapshotPoint>> overviewSnapshotCaffeineCache;
    private final MonitorProperties monitorProperties;

    /**
     * 追加一个采集点；超出保留数则淘汰最旧
     */
    public void push(String timeRange, long collectedAt, long totalRequests, long errorCount, 
                     double avgResponseTime, double qps, double errorRate) {
        Deque<OverviewSnapshotPoint> deque = overviewSnapshotCaffeineCache.get(timeRange, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new OverviewSnapshotPoint(collectedAt, totalRequests, errorCount, 
                                                     avgResponseTime, qps, errorRate));
            while (deque.size() > monitorProperties.getSnapshotRetention()) {
                deque.pollFirst();
            }
        }
    }

    /**
     * 取某时间范围在 [fromSec, toSec] 内的时序点（按时间升序）
     */
    public List<OverviewSnapshotPoint> get(String timeRange, long fromSec, long toSec) {
        Deque<OverviewSnapshotPoint> deque = overviewSnapshotCaffeineCache.getIfPresent(timeRange);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            List<OverviewSnapshotPoint> result = new ArrayList<>();
            for (OverviewSnapshotPoint point : deque) {
                if (point.getCollectedAt() >= fromSec && point.getCollectedAt() <= toSec) {
                    result.add(point);
                }
            }
            return result;
        }
    }

    /**
     * 最近一次采集点（无数据返回 null）
     */
    public OverviewSnapshotPoint latest(String timeRange) {
        Deque<OverviewSnapshotPoint> deque = overviewSnapshotCaffeineCache.getIfPresent(timeRange);
        if (deque == null) {
            return null;
        }
        synchronized (deque) {
            return deque.peekLast();
        }
    }

    /**
     * 清理某时间范围的快照
     */
    public void evict(String timeRange) {
        overviewSnapshotCaffeineCache.invalidate(timeRange);
    }
}
