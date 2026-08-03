package com.ykc.hubble.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.vo.SnapshotPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 大盘时序快照存储：按监控项维护最近一段时间的采集点，
 * 供 alert-data 接口毫秒级读取，避免每次都打 SLS。
 *
 * @author Cloud Eyes Team
 */
@Service
@RequiredArgsConstructor
public class SnapshotCache {

    private final Cache<Long, Deque<SnapshotPoint>> monitorSnapshotCache;
    private final MonitorProperties monitorProperties;

    /**
     * 追加一个采集点；超出保留数则淘汰最旧
     */
    public void push(Long configId, long collectedAt, long logCount) {
        Deque<SnapshotPoint> deque = monitorSnapshotCache.get(configId, k -> new ArrayDeque<>());
        // 同一监控项可能被多次采集，对队列加锁保证时序点顺序正确
        synchronized (deque) {
            deque.addLast(new SnapshotPoint(collectedAt, logCount));
            while (deque.size() > monitorProperties.getSnapshotRetention()) {
                deque.pollFirst();
            }
        }
    }

    /**
     * 取某监控项在 [fromSec, toSec] 内的时序点（按时间升序）
     */
    public List<SnapshotPoint> get(Long configId, long fromSec, long toSec) {
        Deque<SnapshotPoint> deque = monitorSnapshotCache.getIfPresent(configId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            List<SnapshotPoint> result = new ArrayList<>();
            for (SnapshotPoint point : deque) {
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
    public SnapshotPoint latest(Long configId) {
        Deque<SnapshotPoint> deque = monitorSnapshotCache.getIfPresent(configId);
        if (deque == null) {
            return null;
        }
        synchronized (deque) {
            return deque.peekLast();
        }
    }

    /**
     * 监控项删除/禁用时清理其快照
     */
    public void evict(Long configId) {
        monitorSnapshotCache.invalidate(configId);
    }
}
