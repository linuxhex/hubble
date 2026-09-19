package com.ykc.hubble.service;

import com.aliyuncs.arms.model.v20190808.QueryMetricByPageResponse;
import com.ykc.hubble.client.ArmsClient;
import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.entity.LogEntry;
import com.ykc.hubble.vo.ApiDegradationVO;
import com.ykc.hubble.vo.GatewayHotApiVO;
import com.ykc.hubble.vo.GatewayOverviewVO;
import com.ykc.hubble.vo.GatewayTrendVO;
import com.ykc.hubble.vo.OverviewSnapshotPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final SlsQueryClient slsQueryClient;
    private final ArmsClient armsClient;
    private final MonitorProperties monitorProperties;
    private final SlsConfig slsConfig;
    private final OverviewSnapshotCache overviewSnapshotCache;
    private final PageDataCacheService pageDataCacheService;
    @org.springframework.beans.factory.annotation.Qualifier("queryExecutor")
    private final Executor queryExecutor;
    private final AlertPushService alertPushService;
    private final com.ykc.hubble.client.DingTalkClient dingTalkClient;
    private final AlertThresholdService alertThresholdService;

    // 已告警的接口（防抖）：key=apiPath, value=上次告警时间戳
    private final Map<String, Long> trafficAlertLastSent = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${gateway.core-services:}")
    private String coreServicesStr;

    @Value("${gateway.known-services:}")
    private String knownServicesStr;

    private String[] coreServices = new String[0];
    private List<String> knownServices = List.of();

    @PostConstruct
    public void init() {
        if (coreServicesStr != null && !coreServicesStr.isBlank()) {
            coreServices = Arrays.stream(coreServicesStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        }
        if (knownServicesStr != null && !knownServicesStr.isBlank()) {
            knownServices = Arrays.stream(knownServicesStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        log.info("网关服务配置加载完成: {} 个核心服务, {} 个已知服务", coreServices.length, knownServices.size());
    }

    // 接口劣化缓存：key=compareMode, value=[data, timestamp]
    private final Map<String, CacheEntry> degradationCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 流量涨幅缓存：key=compareMode, value=[data, timestamp]
    private final Map<String, CacheEntry> trafficSurgeCache = new java.util.concurrent.ConcurrentHashMap<>();
    // P60排名内存缓存：key=compareMode, value=[data, timestamp]
    private final Map<String, CacheEntry> p60RankingCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 趋势数据内存缓存：key=timeRange
    private final Map<String, TrendCacheEntry> trendCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 热门接口内存缓存：key=timeRange
    private final Map<String, HotApisCacheEntry> hotApisCache = new java.util.concurrent.ConcurrentHashMap<>();
    // 正在加载中的任务标识，防止重复计算
    private final Set<String> loadingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 分钟
    
    // 概览数据缓存：key=timeRange, value=[data, timestamp]
    private final Map<String, OverviewCacheEntry> overviewCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long OVERVIEW_CACHE_TTL_MS = 60 * 1000; // 1 分钟
    /**
     * 估算的平均服务链路长度：每个外部请求在微服务链路中经过的服务数。
     * 用于将全量 ControllerLog 数折算为外部请求数。
     */
    private static final double ESTIMATED_CHAIN_LENGTH = 20.0;
    
    private static class OverviewCacheEntry {
        final GatewayOverviewVO data;
        final long timestamp;
        OverviewCacheEntry(GatewayOverviewVO data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > OVERVIEW_CACHE_TTL_MS;
        }
    }

    @jakarta.annotation.PostConstruct
    public void initCache() {
        if (slsConfig.getAccessKeyId() == null || slsConfig.getAccessKeyId().isBlank()) {
            log.info("SLS 凭证未配置，跳过网关缓存初始化");
            return;
        }
        CompletableFuture.runAsync(() -> {
            log.info("异步初始化所有网关页面缓存...");
            long start = System.currentTimeMillis();
            
            // 1. 初始化接口劣化缓存
            for (String mode : Arrays.asList("day", "week", "month")) {
                try {
                    List<ApiDegradationVO> data = loadDegradation(mode);
                    if (data.isEmpty()) {
                        log.info("初始化劣化 {} 模式返回空数据，跳过缓存", mode);
                        continue;
                    }
                    degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save("gateway_degradation", mode, data, 30);
                    log.info("缓存劣化 {} 模式完成，{} 条数据", mode, data.size());
                } catch (Exception e) {
                    log.warn("初始化劣化缓存 {} 失败: {}", mode, e.getMessage());
                }
            }
            
            // 2. 初始化 P60 排名缓存
            for (String mode : Arrays.asList("day", "week", "month")) {
                try {
                    List<ApiDegradationVO> data = loadP60Ranking(mode);
                    if (data.isEmpty()) {
                        log.info("初始化 P60 {} 模式返回空数据，跳过缓存", mode);
                        continue;
                    }
                    p60RankingCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save("gateway_p60_ranking", mode, data, 30);
                    log.info("缓存 P60 {} 模式完成，{} 条数据", mode, data.size());
                } catch (Exception e) {
                    log.warn("初始化 P60 缓存 {} 失败: {}", mode, e.getMessage());
                }
            }
            
            // 3. 初始化趋势缓存
            for (String range : Arrays.asList("1h", "24h", "7d")) {
                try {
                    GatewayTrendVO data = loadTrend(range);
                    if (data.getTimestamps() == null || data.getTimestamps().isEmpty()) {
                        log.info("初始化趋势 {} 返回空数据，跳过缓存", range);
                        continue;
                    }
                    trendCache.put(range, new TrendCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save("gateway_trend", range, data, 5);
                    log.info("缓存趋势 {} 完成", range);
                } catch (Exception e) {
                    log.warn("初始化趋势缓存 {} 失败: {}", range, e.getMessage());
                }
            }
            
            // 4. 初始化热门接口缓存
            for (String range : Arrays.asList("1h", "24h")) {
                try {
                    List<GatewayHotApiVO> data = loadHotApis(range);
                    if (data.isEmpty()) {
                        log.info("初始化热门接口 {} 返回空数据，跳过缓存", range);
                        continue;
                    }
                    hotApisCache.put(range, new HotApisCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save("gateway_hot_apis", range, data, 30);
                    log.info("缓存热门接口 {} 完成，{} 条", range, data.size());
                } catch (Exception e) {
                    log.warn("初始化热门接口缓存 {} 失败: {}", range, e.getMessage());
                }
            }
            
            // 5. 初始化概览缓存
            for (String range : Arrays.asList("24h", "7d", "30d")) {
                try {
                    GatewayOverviewVO data = queryOverviewData(range);
                    if (data.getTotalRequests() == 0) {
                        log.info("初始化概览 {} 返回空数据，跳过缓存", range);
                        continue;
                    }
                    overviewCache.put(range, new OverviewCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save("gateway_overview", range, data, 30);
                    log.info("缓存概览 {} 完成", range);
                } catch (Exception e) {
                    log.warn("初始化概览缓存 {} 失败: {}", range, e.getMessage());
                }
            }
            
            // 6. 初始化流量涨幅缓存
            for (String mode : Arrays.asList("day", "week")) {
                try {
                    List<ApiDegradationVO> data = loadTrafficSurge(mode);
                    if (data.isEmpty()) {
                        log.info("初始化流量涨幅 {} 模式返回空数据，跳过缓存", mode);
                        continue;
                    }
                    trafficSurgeCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save("gateway_traffic_surge", mode, data, 30);
                    log.info("缓存流量涨幅 {} 完成，{} 条", mode, data.size());
                } catch (Exception e) {
                    log.warn("初始化流量涨幅缓存 {} 失败: {}", mode, e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("所有网关页面缓存异步初始化完成，耗时 {}ms", elapsed);
        }, queryExecutor);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5 * 60 * 1000) // 每 5 分钟刷新
    public void refreshCache() {
        log.debug("后台刷新接口劣化缓存...");
        for (String mode : Arrays.asList("day", "week", "month")) {
            try {
                List<ApiDegradationVO> data = loadDegradation(mode);
                if (data.isEmpty()) {
                    CacheEntry existing = degradationCache.get(mode);
                    if (existing != null && !existing.data.isEmpty()) {
                        log.info("刷新 {} 返回空数据，保留已有缓存 {} 条", mode, existing.data.size());
                        continue;
                    }
                }
                degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                if (!data.isEmpty()) {
                    pageDataCacheService.save("gateway_degradation", mode, data, 30);
                }
                log.debug("刷新 {} 缓存完成，{} 条", mode, data.size());
            } catch (Exception e) {
                log.warn("刷新缓存 {} 失败: {}", mode, e.getMessage());
            }
        }
        // 刷新流量涨幅缓存
        for (String mode : Arrays.asList("day", "week")) {
            try {
                List<ApiDegradationVO> data = loadTrafficSurge(mode);
                if (data.isEmpty()) {
                    CacheEntry existing = trafficSurgeCache.get(mode);
                    if (existing != null && !existing.data.isEmpty()) {
                        log.info("刷新流量涨幅 {} 返回空数据，保留已有缓存 {} 条", mode, existing.data.size());
                        continue;
                    }
                }
                trafficSurgeCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                if (!data.isEmpty()) {
                    pageDataCacheService.save("gateway_traffic_surge", mode, data, 30);
                }
                log.debug("刷新流量涨幅 {} 缓存完成，{} 条", mode, data.size());
            } catch (Exception e) {
                log.warn("刷新流量涨幅缓存 {} 失败: {}", mode, e.getMessage());
            }
        }
    }

    private static class CacheEntry {
        final List<ApiDegradationVO> data;
        final long timestamp;
        CacheEntry(List<ApiDegradationVO> data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static class TrendCacheEntry {
        final GatewayTrendVO data;
        final long timestamp;
        TrendCacheEntry(GatewayTrendVO data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static class HotApisCacheEntry {
        final List<GatewayHotApiVO> data;
        final long timestamp;
        HotApisCacheEntry(List<GatewayHotApiVO> data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public GatewayOverviewVO overview(String timeRange) {
        // 先检查内存缓存，有则立即返回
        OverviewCacheEntry cached = overviewCache.get(timeRange);
        if (cached != null) {
            // 如果缓存未过期，直接返回
            if (!cached.isExpired()) {
                log.debug("返回概览内存缓存数据: timeRange={}, age={}ms", timeRange, System.currentTimeMillis() - cached.timestamp);
                return cached.data;
            }
        }
        
        // 内存缓存过期或不存在，检查数据库缓存
        String pageKey = "gateway_overview";
        String dataKey = timeRange;
        GatewayOverviewVO dbCached = pageDataCacheService.get(pageKey, dataKey, GatewayOverviewVO.class);
        if (dbCached != null) {
            log.info("返回概览数据库缓存数据: timeRange={}", timeRange);
            // 更新内存缓存
            overviewCache.put(timeRange, new OverviewCacheEntry(dbCached, System.currentTimeMillis()));
            // 异步刷新数据库缓存
            CompletableFuture.runAsync(() -> {
                try {
                    GatewayOverviewVO freshData = queryOverviewData(timeRange);
                    if (freshData.getTotalRequests() == 0 && dbCached.getTotalRequests() > 0) {
                        log.info("概览后台刷新返回空数据，保留已有缓存: timeRange={}", timeRange);
                        return;
                    }
                    overviewCache.put(timeRange, new OverviewCacheEntry(freshData, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, freshData, 30);
                    log.info("后台刷新概览数据库缓存: timeRange={}", timeRange);
                } catch (Exception e) {
                    log.warn("后台刷新概览数据库缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return dbCached;
        }
        
        // 内存缓存存在但过期，后台异步更新，返回旧数据
        if (cached != null) {
            log.debug("概览缓存已过期，后台更新: timeRange={}", timeRange);
            CompletableFuture.runAsync(() -> {
                try {
                    GatewayOverviewVO freshData = queryOverviewData(timeRange);
                    if (freshData.getTotalRequests() == 0 && cached.data.getTotalRequests() > 0) {
                        log.info("概览后台刷新返回空数据，保留已有缓存: timeRange={}", timeRange);
                        return;
                    }
                    overviewCache.put(timeRange, new OverviewCacheEntry(freshData, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, freshData, 30);
                    log.info("概览缓存已更新: timeRange={}", timeRange);
                } catch (Exception e) {
                    log.warn("后台更新概览缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            // 返回旧的缓存数据
            return cached.data;
        }
        
        // 没有缓存，先从快照缓存中获取历史数据立即返回
        try {
            OverviewSnapshotPoint snapshot = overviewSnapshotCache.latest(timeRange);
            if (snapshot != null) {
                GatewayOverviewVO vo = new GatewayOverviewVO();
                vo.setTotalRequests(snapshot.getTotalRequests());
                vo.setAvgResponseTime(snapshot.getAvgResponseTime());
                vo.setQps(snapshot.getQps());
                vo.setErrorRate(snapshot.getErrorRate());
                overviewCache.put(timeRange, new OverviewCacheEntry(vo, System.currentTimeMillis()));
                pageDataCacheService.save(pageKey, dataKey, vo, 30);
                log.info("使用快照数据返回概览: timeRange={}, totalRequests={}", timeRange, snapshot.getTotalRequests());
                CompletableFuture.runAsync(() -> {
                    try {
                        GatewayOverviewVO freshData = queryOverviewData(timeRange);
                        overviewCache.put(timeRange, new OverviewCacheEntry(freshData, System.currentTimeMillis()));
                        pageDataCacheService.save(pageKey, dataKey, freshData, 30);
                    } catch (Exception ex) {
                        log.warn("后台刷新概览数据失败: {}", ex.getMessage());
                    }
                }, queryExecutor);
                return vo;
            }
        } catch (Exception e) {
            log.warn("读取快照数据失败: {}", e.getMessage());
        }

        // 快照也没有，同步查询并缓存
        try {
            GatewayOverviewVO data = queryOverviewData(timeRange);
            overviewCache.put(timeRange, new OverviewCacheEntry(data, System.currentTimeMillis()));
            pageDataCacheService.save(pageKey, dataKey, data, 30);
            log.info("概览数据已缓存: timeRange={}", timeRange);
            return data;
        } catch (Exception e) {
            log.error("查询概览数据失败: {}", e.getMessage(), e);
            GatewayOverviewVO empty = new GatewayOverviewVO();
            empty.setTotalRequests(0);
            empty.setQps(0.0);
            empty.setErrorRate(0.0);
            empty.setAvgResponseTime(0.0);
            return empty;
        }
    }
    
    /**
     * 实际查询概览数据（ARMS 优先，SLS 降级）
     */
    private GatewayOverviewVO queryOverviewData(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long seconds = parseTimeRange(timeRange);
        long from = now - seconds;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        // 根据时间范围确定聚合粒度（ARMS 支持的标准粒度）
        // ARMS 只支持特定的间隔值：60, 300, 900, 3600, 86400
        int intervalInSec;
        int rtIntervalInSec;
        if (seconds <= 3600) { // 1小时内
            intervalInSec = 3600; // 按小时
            rtIntervalInSec = 3600;
        } else if (seconds <= 86400) { // 24小时内
            intervalInSec = 3600;
            rtIntervalInSec = 3600;
        } else { // 超过24小时
            intervalInSec = 86400;
            rtIntervalInSec = 86400;
        }

        // 优先从 ARMS 获取数据
        try {
            long fromMs = from * 1000;
            long toMs = now * 1000;
            
            log.info("查询概览数据: timeRange={}, intervalInSec={}, rtIntervalInSec={}, fromMs={}, toMs={}", 
                timeRange, intervalInSec, rtIntervalInSec, fromMs, toMs);
            
            // 1. 查询调用次数和错误数（支持细粒度）
            var response = armsClient.queryMetrics(
                "appstat.transaction",
                Arrays.asList("count", "error"),
                fromMs,
                toMs,
                null,
                intervalInSec
            );
            
            if (response != null && response.getData() != null && response.getData().getItems() != null && !response.getData().getItems().isEmpty()) {
                long errorCount = 0;
                double qpsRateSum = 0;
                int bucketCount = 0;
                
                for (var itemObj : response.getData().getItems()) {
                    if (itemObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> item = (Map<Object, Object>) itemObj;
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                        if (measures != null) {
                            double rate = ((Number) measures.getOrDefault("count", 0L)).doubleValue();
                            long err = ((Number) measures.getOrDefault("error", 0L)).longValue();
                            qpsRateSum += rate;
                            errorCount += err;
                            bucketCount++;
                        }
                    }
                }
                
                double avgQps = bucketCount > 0 ? qpsRateSum / bucketCount : 0;
                long totalRequests = Math.round(avgQps * seconds);
                
                // 2. 查询平均响应时间（使用较粗的粒度）
                double avgTime = 0;
                try {
                    var rtResponse = armsClient.queryMetrics(
                        "appstat.transaction",
                        Arrays.asList("rt", "count"),
                        fromMs,
                        toMs,
                        null,
                        rtIntervalInSec
                    );
                    
                    if (rtResponse != null && rtResponse.getData() != null && rtResponse.getData().getItems() != null) {
                        double totalRt = 0;
                        long rtCount = 0;
                        for (var itemObj : rtResponse.getData().getItems()) {
                            if (itemObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<Object, Object> item = (Map<Object, Object>) itemObj;
                                @SuppressWarnings("unchecked")
                                Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                                if (measures != null) {
                                    double rt = ((Number) measures.getOrDefault("rt", 0.0)).doubleValue();
                                    long count = ((Number) measures.getOrDefault("count", 0L)).longValue();
                                    totalRt += rt * count;
                                    rtCount += count;
                                }
                            }
                        }
                        avgTime = rtCount > 0 ? totalRt / rtCount : 0;
                    }
                } catch (Exception e) {
                    log.warn("查询 RT 失败，使用 SLS 降级: {}", e.getMessage());
                }
                
                GatewayOverviewVO vo = new GatewayOverviewVO();
                vo.setTotalRequests(totalRequests);
                vo.setQps(Math.round(avgQps * 100.0) / 100.0);
                vo.setAvgResponseTime(Math.round(avgTime * 10.0) / 10.0);

                long slsErrorCount = 0;
                long slsAllTotal = 0;
                try {
                    String slsErrorQuery = "level: ERROR | SELECT COUNT(*) as total";
                    var slsErrorResult = slsQueryClient.queryAnalytics(logstore, slsErrorQuery, from, now, 1);
                    if (!slsErrorResult.isEmpty()) {
                        slsErrorCount = Long.parseLong(slsErrorResult.get(0).getOrDefault("total", "0"));
                    }
                    String slsTotalQuery = "* | SELECT COUNT(*) as total";
                    var slsTotalResult = slsQueryClient.queryAnalytics(logstore, slsTotalQuery, from, now, 1);
                    if (!slsTotalResult.isEmpty()) {
                        slsAllTotal = Long.parseLong(slsTotalResult.get(0).getOrDefault("total", "0"));
                    }
                } catch (Exception e) {
                    log.warn("SLS 补充 ERROR 数失败: {}", e.getMessage());
                }
                long effectiveErrorCount = Math.max(errorCount, slsErrorCount);
                vo.setErrorRate(slsAllTotal == 0 ? (qpsRateSum == 0 ? 0 : effectiveErrorCount * 100.0 / qpsRateSum)
                        : effectiveErrorCount * 100.0 / slsAllTotal);
                log.info("概览错误率: armsError={}, slsError={}, slsTotal={}, effectiveError={}, errorRate={}", 
                    errorCount, slsErrorCount, slsAllTotal, effectiveErrorCount, vo.getErrorRate());

                // 趋势计算（与上一周期对比）
                long prevFrom = from - seconds;
                var prevResponse = armsClient.queryMetrics(
                    "appstat.transaction",
                    Arrays.asList("count", "error"),
                    prevFrom * 1000,
                    fromMs,
                    null,
                    intervalInSec
                );
                
                double prevQpsRateSum = 0;
                long prevErrors = 0;
                int prevBucketCount = 0;
                
                if (prevResponse != null && prevResponse.getData() != null && prevResponse.getData().getItems() != null) {
                    for (var itemObj : prevResponse.getData().getItems()) {
                        if (itemObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> item = (Map<Object, Object>) itemObj;
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                            if (measures != null) {
                                prevQpsRateSum += ((Number) measures.getOrDefault("count", 0L)).doubleValue();
                                prevErrors += ((Number) measures.getOrDefault("error", 0L)).longValue();
                                prevBucketCount++;
                            }
                        }
                    }
                }
                double prevQps = prevBucketCount > 0 ? prevQpsRateSum / prevBucketCount : 0;
                long prevTotal = Math.round(prevQps * seconds);
                
                // 查询上期 RT
                double prevAvg = 0;
                try {
                    var prevRtResponse = armsClient.queryMetrics(
                        "appstat.transaction",
                        Arrays.asList("rt", "count"),
                        prevFrom * 1000,
                        fromMs,
                        null,
                        rtIntervalInSec
                    );
                    
                    if (prevRtResponse != null && prevRtResponse.getData() != null && prevRtResponse.getData().getItems() != null) {
                        double prevTotalRt = 0;
                        long prevRtCount = 0;
                        for (var itemObj : prevRtResponse.getData().getItems()) {
                            if (itemObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<Object, Object> item = (Map<Object, Object>) itemObj;
                                @SuppressWarnings("unchecked")
                                Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                                if (measures != null) {
                                    double rt = ((Number) measures.getOrDefault("rt", 0.0)).doubleValue();
                                    long count = ((Number) measures.getOrDefault("count", 0L)).longValue();
                                    prevTotalRt += rt * count;
                                    prevRtCount += count;
                                }
                            }
                        }
                        prevAvg = prevRtCount > 0 ? prevTotalRt / prevRtCount : 0;
                    }
                } catch (Exception e) {
                    log.warn("查询上期 RT 失败: {}", e.getMessage());
                }
                
                vo.setTotalTrend(prevTotal > 0 ? (totalRequests - prevTotal) * 100.0 / prevTotal : 0);
                vo.setAvgTrend(prevAvg > 0 ? (avgTime - prevAvg) * 100.0 / prevAvg : 0);
                vo.setErrorTrend(qpsRateSum == 0 ? 0 : (errorCount - prevErrors) * 100.0 / Math.max(errorCount, 1));
                vo.setQpsTrend(prevQps > 0 ? (avgQps - prevQps) * 100.0 / prevQps : 0);
                
                log.info("从 ARMS 获取概览数据成功: totalRequests={}, qps={}, avgRt={}", totalRequests, vo.getQps(), avgTime);
                return vo;
            }
        } catch (Exception e) {
            log.warn("从 ARMS 获取概览数据失败，降级到 SLS: {}", e.getMessage());
        }

        // ARMS 失败时降级到 SLS
        return overviewFromSls(timeRange, now, seconds, from, logstore);
    }
    
    private GatewayOverviewVO overviewFromSls(String timeRange, long now, long seconds, long from, String logstore) {
        long totalRequests = 0;
        long errorCount = 0;
        long allLogsTotal = 0;
        
        try {
            String requestFilter = "ControllerLog and apiUrl";
            String countQuery = requestFilter + " | SELECT COUNT(*) as total";
            List<Map<String, String>> countResult = slsQueryClient.queryAnalytics(logstore, countQuery, from, now, 1);
            if (!countResult.isEmpty()) {
                totalRequests = Long.parseLong(countResult.get(0).getOrDefault("total", "0"));
            }
            
            String allLogsQuery = "* | SELECT COUNT(*) as total";
            List<Map<String, String>> allLogsResult = slsQueryClient.queryAnalytics(logstore, allLogsQuery, from, now, 1);
            if (!allLogsResult.isEmpty()) {
                allLogsTotal = Long.parseLong(allLogsResult.get(0).getOrDefault("total", "0"));
            }
            
            String errorQuery = "level: ERROR | SELECT COUNT(*) as total";
            List<Map<String, String>> errorResult = slsQueryClient.queryAnalytics(logstore, errorQuery, from, now, 1);
            if (!errorResult.isEmpty()) {
                errorCount = Long.parseLong(errorResult.get(0).getOrDefault("total", "0"));
            }
        } catch (Exception e) {
            log.warn("SLS 分析查询失败，使用 GetHistograms 降级: {}", e.getMessage());
            totalRequests = slsQueryClient.countLogstore(logstore, "ControllerLog and apiUrl", from, now);
            errorCount = slsQueryClient.countLogstore(logstore, "level: ERROR", from, now);
            allLogsTotal = slsQueryClient.countLogstore(logstore, "*", from, now);
        }

        // 全量 ControllerLog 包含微服务链路中每个服务的日志，需除以链路长度折算外部请求数
        long externalTotalRequests = Math.round(totalRequests / ESTIMATED_CHAIN_LENGTH);

        // 查询最近1分钟的请求数来计算当前QPS
        double currentQps = 0;
        try {
            String qpsQuery = "ControllerLog and apiUrl | SELECT COUNT(*) as total";
            List<Map<String, String>> qpsResult = slsQueryClient.queryAnalytics(logstore, qpsQuery, now - 60, now, 1);
            if (!qpsResult.isEmpty()) {
                long lastMinuteCount = Long.parseLong(qpsResult.get(0).getOrDefault("total", "0"));
                currentQps = lastMinuteCount / 60.0 / ESTIMATED_CHAIN_LENGTH;
            }
        } catch (Exception e) {
            log.warn("SLS 查询当前QPS失败: {}", e.getMessage());
            currentQps = seconds > 0 ? (double) externalTotalRequests / seconds : 0;
        }

        GatewayOverviewVO vo = new GatewayOverviewVO();
        vo.setTotalRequests(externalTotalRequests);
        vo.setQps(currentQps);
        vo.setErrorRate(allLogsTotal == 0 ? 0 : errorCount * 100.0 / allLogsTotal);

        // 采样部分日志计算平均响应时间（取最近的日志）
        List<LogEntry> sampleLogs = queryLogs(logstore, "*", from, now, 0, 100);
        double avgTime = sampleLogs.stream()
                .mapToLong(l -> extractResponseTime(l.getMessage()))
                .average()
                .orElse(0);
        vo.setAvgResponseTime(Math.round(avgTime * 10.0) / 10.0);

        // 趋势计算（与上一周期对比）
        long prevFrom = from - seconds;
        long prevTotal = 0;
        long prevErrors = 0;
        
        try {
            String requestFilter = "ControllerLog and apiUrl";
            String prevCountQuery = requestFilter + " | SELECT COUNT(*) as total";
            List<Map<String, String>> prevCountResult = slsQueryClient.queryAnalytics(logstore, prevCountQuery, prevFrom, from, 1);
            if (!prevCountResult.isEmpty()) {
                prevTotal = Long.parseLong(prevCountResult.get(0).getOrDefault("total", "0"));
            }
            
            String prevErrorQuery = "level: ERROR | SELECT COUNT(*) as total";
            List<Map<String, String>> prevErrorResult = slsQueryClient.queryAnalytics(logstore, prevErrorQuery, prevFrom, from, 1);
            if (!prevErrorResult.isEmpty()) {
                prevErrors = Long.parseLong(prevErrorResult.get(0).getOrDefault("total", "0"));
            }
        } catch (Exception e) {
            log.warn("SLS 上期分析查询失败: {}", e.getMessage());
            prevTotal = slsQueryClient.countLogstore(logstore, "ControllerLog and apiUrl", prevFrom, from);
            prevErrors = slsQueryClient.countLogstore(logstore, "(ControllerLog and apiUrl) and ERROR", prevFrom, from);
        }
        
        long externalPrevTotal = Math.round(prevTotal / ESTIMATED_CHAIN_LENGTH);
        
        List<LogEntry> prevSampleLogs = queryLogs(logstore, "*", prevFrom, from, 0, 100);
        double prevAvg = prevSampleLogs.stream()
                .mapToLong(l -> extractResponseTime(l.getMessage()))
                .average()
                .orElse(0);
        
        double prevQps = seconds > 0 ? (double) externalPrevTotal / seconds : 0;
        vo.setTotalTrend(externalPrevTotal > 0 ? (externalTotalRequests - externalPrevTotal) * 100.0 / externalPrevTotal : 0);
        vo.setAvgTrend(prevAvg > 0 ? (avgTime - prevAvg) * 100.0 / prevAvg : 0);
        vo.setErrorTrend(prevErrors > 0 ? (errorCount - prevErrors) * 100.0 / prevErrors : 0);
        vo.setQpsTrend(prevQps > 0 ? (vo.getQps() - prevQps) * 100.0 / prevQps : 0);
        
        log.info("SLS 概览数据: rawTotal={}, externalTotal={}, qps={}, errorRate={}%, avgRt={}", totalRequests, externalTotalRequests, String.format("%.1f", currentQps), String.format("%.2f", vo.getErrorRate()), avgTime);
        return vo;
    }

    public GatewayTrendVO trend(String timeRange) {
        String key = timeRange != null ? timeRange : "1h";
        TrendCacheEntry entry = trendCache.get(key);

        if (entry != null && !entry.isExpired()) {
            log.debug("返回趋势内存缓存: timeRange={}", key);
            return entry.data;
        }

        String pageKey = "gateway_trend";
        String dataKey = key;
        GatewayTrendVO dbCached = pageDataCacheService.get(pageKey, dataKey, GatewayTrendVO.class);
        if (dbCached != null) {
            log.info("返回趋势数据库缓存: timeRange={}", key);
            trendCache.put(key, new TrendCacheEntry(dbCached, System.currentTimeMillis()));
            CompletableFuture.runAsync(() -> {
                try {
                    GatewayTrendVO data = loadTrend(timeRange);
                    if ((data.getTimestamps() == null || data.getTimestamps().isEmpty()) && dbCached.getTimestamps() != null && !dbCached.getTimestamps().isEmpty()) {
                        log.info("趋势后台刷新返回空数据，保留已有缓存: timeRange={}", timeRange);
                        return;
                    }
                    trendCache.put(key, new TrendCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新趋势缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return dbCached;
        }

        if (entry != null) {
            log.info("趋势内存缓存过期，返回旧数据并后台刷新: timeRange={}", key);
            CompletableFuture.runAsync(() -> {
                try {
                    GatewayTrendVO data = loadTrend(timeRange);
                    if ((data.getTimestamps() == null || data.getTimestamps().isEmpty()) && entry.data.getTimestamps() != null && !entry.data.getTimestamps().isEmpty()) {
                        log.info("趋势后台刷新返回空数据，保留已有缓存: timeRange={}", timeRange);
                        return;
                    }
                    trendCache.put(key, new TrendCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新趋势缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return entry.data;
        }

        String loadingKey = "trend_" + key;
        if (loadingKeys.add(loadingKey)) {
            log.info("趋势数据无缓存，后台加载: timeRange={}", key);
            CompletableFuture.runAsync(() -> {
                try {
                    GatewayTrendVO result = loadTrend(timeRange);
                    trendCache.put(key, new TrendCacheEntry(result, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, result, 5);
                    log.info("趋势数据后台加载完成: timeRange={}", key);
                } catch (Exception e) {
                    log.warn("趋势数据后台加载失败: timeRange={}, error={}", key, e.getMessage());
                } finally {
                    loadingKeys.remove(loadingKey);
                }
            }, queryExecutor);
        } else {
            log.info("趋势数据正在加载中，跳过重复请求: timeRange={}", key);
        }
        GatewayTrendVO empty = new GatewayTrendVO();
        empty.setTimestamps(new ArrayList<>());
        empty.setInfoCounts(new ArrayList<>());
        empty.setWarnCounts(new ArrayList<>());
        empty.setErrorCounts(new ArrayList<>());
        return empty;
    }

    private GatewayTrendVO loadTrend(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long seconds = parseTimeRange(timeRange);
        long from = now - seconds;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        // 优先从 ARMS 获取时间轴和总量，再从 SLS 补充 WARN/ERROR 日志级别数据
        try {
            long fromMs = from * 1000;
            long toMs = now * 1000;
            
            int intervalInSec;
            if (seconds <= 3600) {
                intervalInSec = 3600;
            } else if (seconds <= 86400) {
                intervalInSec = 3600;
            } else {
                intervalInSec = 86400;
            }
            
            var response = armsClient.queryMetrics(
                "appstat.transaction",
                Arrays.asList("count", "error"),
                fromMs,
                toMs,
                null,
                intervalInSec
            );
            
            if (response != null && response.getData() != null && response.getData().getItems() != null && !response.getData().getItems().isEmpty()) {
                Map<String, long[]> bucketMap = new LinkedHashMap<>();
                DateTimeFormatter fmt = seconds <= 86400 
                    ? DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                    : DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.systemDefault());
                
                for (var itemObj : response.getData().getItems()) {
                    if (itemObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> item = (Map<Object, Object>) itemObj;
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                        Long timestamp = (Long) item.get("time");
                        if (measures != null && timestamp != null) {
                            // 过滤掉未来的时间点
                            if (timestamp > toMs) {
                                continue;
                            }
                            String label = fmt.format(Instant.ofEpochMilli(timestamp));
                            long count = ((Number) measures.getOrDefault("count", 0L)).longValue();
                            bucketMap.put(label, new long[]{count, 0, 0}); // total, warn, error
                        }
                    }
                }

                String timeBucketExpr = seconds <= 86400
                    ? "date_format(__time__ - __time__ % 3600, '%H:%i')"
                    : "date_format(__time__ - __time__ % 86400, '%m-%d')";

                try {
                    String errorQuery = "level: ERROR | SELECT " + timeBucketExpr + " as time_bucket, COUNT(*) as cnt GROUP BY time_bucket ORDER BY time_bucket";
                    List<Map<String, String>> errorResults = slsQueryClient.queryAnalytics(logstore, errorQuery, from, now, 1000);
                    for (Map<String, String> row : errorResults) {
                        String tb = row.getOrDefault("time_bucket", "");
                        long cnt = Long.parseLong(row.getOrDefault("cnt", "0"));
                        long[] bucket = bucketMap.get(tb);
                        if (bucket != null) {
                            bucket[2] = cnt;
                        }
                    }

                    String warnQuery = "level: WARN | SELECT " + timeBucketExpr + " as time_bucket, COUNT(*) as cnt GROUP BY time_bucket ORDER BY time_bucket";
                    List<Map<String, String>> warnResults = slsQueryClient.queryAnalytics(logstore, warnQuery, from, now, 1000);
                    for (Map<String, String> row : warnResults) {
                        String tb = row.getOrDefault("time_bucket", "");
                        long cnt = Long.parseLong(row.getOrDefault("cnt", "0"));
                        long[] bucket = bucketMap.get(tb);
                        if (bucket != null) {
                            bucket[1] = cnt;
                        }
                    }
                    log.info("SLS 补充 WARN/ERROR 趋势: error={} 点, warn={} 点", errorResults.size(), warnResults.size());
                } catch (Exception e) {
                    log.warn("SLS 补充 WARN/ERROR 失败: {}", e.getMessage());
                }

                GatewayTrendVO vo = new GatewayTrendVO();
                vo.setTimestamps(new ArrayList<>());
                vo.setInfoCounts(new ArrayList<>());
                vo.setWarnCounts(new ArrayList<>());
                vo.setErrorCounts(new ArrayList<>());
                
                for (Map.Entry<String, long[]> entry : bucketMap.entrySet()) {
                    long[] counts = entry.getValue();
                    long total = counts[0];
                    long warn = counts[1];
                    long error = counts[2];
                    long info = Math.max(0, total - warn - error);

                    vo.getTimestamps().add(entry.getKey());
                    vo.getInfoCounts().add(info);
                    vo.getWarnCounts().add(warn);
                    vo.getErrorCounts().add(error);
                }
                
                trimIncompleteLastPoint(vo, seconds);
                log.info("从 ARMS+SLS 获取趋势数据成功: buckets={}", bucketMap.size());
                return vo;
            }
        } catch (Exception e) {
            log.warn("从 ARMS 获取趋势数据失败，降级到 SLS: {}", e.getMessage());
        }

        // ARMS 失败时降级到 SLS
        GatewayTrendVO slsResult = trendFromSls(timeRange, now, seconds, from, logstore);
        trimIncompleteLastPoint(slsResult, seconds);
        return slsResult;
    }

    private void trimIncompleteLastPoint(GatewayTrendVO vo, long seconds) {
        if (vo == null || vo.getTimestamps() == null || vo.getTimestamps().isEmpty()) return;
        if (seconds > 86400) return; // 按天展示时不裁剪

        long nowEpoch = System.currentTimeMillis() / 1000;
        long currentHourEpoch = nowEpoch - (nowEpoch % 3600);
        
        // 过滤掉所有未来的时间点（保留当前小时）
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
        String currentHourLabel = fmt.format(Instant.ofEpochSecond(currentHourEpoch));
        
        List<String> timestamps = vo.getTimestamps();
        List<Long> infoCounts = vo.getInfoCounts();
        List<Long> warnCounts = vo.getWarnCounts();
        List<Long> errorCounts = vo.getErrorCounts();
        
        // 从后往前遍历，移除所有在当前小时之后的时间点
        int i = timestamps.size() - 1;
        while (i >= 0) {
            String ts = timestamps.get(i);
            // 比较小时标签，如果大于当前小时则移除
            if (ts.compareTo(currentHourLabel) > 0) {
                timestamps.remove(i);
                infoCounts.remove(i);
                warnCounts.remove(i);
                errorCounts.remove(i);
                log.debug("裁剪未来时刻: {}", ts);
            }
            i--;
        }
    }
    
    private GatewayTrendVO trendFromSls(String timeRange, long now, long seconds, long from, String logstore) {
        String timeUnit;
        if (seconds <= 3600) {
            timeUnit = "minute";
        } else if (seconds <= 86400) {
            timeUnit = "hour";
        } else {
            timeUnit = "day";
        }

        String timeBucketExpr = switch (timeUnit) {
            case "minute" -> "date_format(__time__ - __time__ % 60, '%H:%i')";
            case "hour" -> "date_format(__time__ - __time__ % 3600, '%H:%i')";
            default -> "date_format(__time__ - __time__ % 86400, '%m-%d')";
        };

        try {
            Map<String, long[]> bucketMap = new LinkedHashMap<>();

            String totalQuery = "* | SELECT " + timeBucketExpr + " as time_bucket, COUNT(*) as cnt GROUP BY time_bucket ORDER BY time_bucket";
            List<Map<String, String>> totalResults = slsQueryClient.queryAnalytics(logstore, totalQuery, from, now, 1000);
            for (Map<String, String> row : totalResults) {
                String tb = row.getOrDefault("time_bucket", "");
                long total = Long.parseLong(row.getOrDefault("cnt", "0"));
                bucketMap.computeIfAbsent(tb, k -> new long[3])[0] = total;
            }

            String errorQuery = "level: ERROR | SELECT " + timeBucketExpr + " as time_bucket, COUNT(*) as cnt GROUP BY time_bucket ORDER BY time_bucket";
            List<Map<String, String>> errorResults = slsQueryClient.queryAnalytics(logstore, errorQuery, from, now, 1000);
            for (Map<String, String> row : errorResults) {
                String tb = row.getOrDefault("time_bucket", "");
                long cnt = Long.parseLong(row.getOrDefault("cnt", "0"));
                bucketMap.computeIfAbsent(tb, k -> new long[3])[2] = cnt;
            }

            String warnQuery = "level: WARN | SELECT " + timeBucketExpr + " as time_bucket, COUNT(*) as cnt GROUP BY time_bucket ORDER BY time_bucket";
            List<Map<String, String>> warnResults = slsQueryClient.queryAnalytics(logstore, warnQuery, from, now, 1000);
            for (Map<String, String> row : warnResults) {
                String tb = row.getOrDefault("time_bucket", "");
                long cnt = Long.parseLong(row.getOrDefault("cnt", "0"));
                bucketMap.computeIfAbsent(tb, k -> new long[3])[1] = cnt;
            }

            log.info("SLS 趋势查询: total={}, error={}, warn={} 个时间点, timeUnit={}",
                    totalResults.size(), errorResults.size(), warnResults.size(), timeUnit);

            GatewayTrendVO vo = new GatewayTrendVO();
            vo.setTimestamps(new ArrayList<>());
            vo.setInfoCounts(new ArrayList<>());
            vo.setWarnCounts(new ArrayList<>());
            vo.setErrorCounts(new ArrayList<>());

            for (Map.Entry<String, long[]> entry : bucketMap.entrySet()) {
                long[] counts = entry.getValue();
                long total = counts[0];
                long warn = counts[1];
                long error = counts[2];
                long info = Math.max(0, total - error - warn);

                vo.getTimestamps().add(entry.getKey());
                vo.getInfoCounts().add(info);
                vo.getWarnCounts().add(warn);
                vo.getErrorCounts().add(error);
            }

            return vo;
        } catch (Exception e) {
            log.error("查询趋势数据失败", e);
            GatewayTrendVO vo = new GatewayTrendVO();
            vo.setTimestamps(new ArrayList<>());
            vo.setInfoCounts(new ArrayList<>());
            vo.setWarnCounts(new ArrayList<>());
            vo.setErrorCounts(new ArrayList<>());
            return vo;
        }
    }

    public List<GatewayHotApiVO> hotApis(String timeRange) {
        String tr = timeRange != null ? timeRange : "1h";
        HotApisCacheEntry entry = hotApisCache.get(tr);

        if (entry != null && !entry.isExpired()) {
            log.debug("返回热门接口内存缓存: timeRange={}, size={}", tr, entry.data.size());
            return entry.data;
        }

        String pageKey = "gateway_hot_apis";
        String dataKey = tr;
        List<GatewayHotApiVO> dbCached = pageDataCacheService.getList(pageKey, dataKey, GatewayHotApiVO.class);
        if (dbCached != null) {
            log.info("返回热门接口数据库缓存: timeRange={}, size={}", tr, dbCached.size());
            hotApisCache.put(tr, new HotApisCacheEntry(dbCached, System.currentTimeMillis()));
            CompletableFuture.runAsync(() -> {
                try {
                    List<GatewayHotApiVO> data = loadHotApis(timeRange);
                    if (data.isEmpty() && !dbCached.isEmpty()) {
                        log.info("热门接口后台刷新返回空数据，保留已有缓存: timeRange={}", timeRange);
                        return;
                    }
                    hotApisCache.put(tr, new HotApisCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新热门接口缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return dbCached;
        }

        if (entry != null) {
            log.info("热门接口内存缓存过期，返回旧数据并后台刷新: timeRange={}", tr);
            CompletableFuture.runAsync(() -> {
                try {
                    List<GatewayHotApiVO> data = loadHotApis(timeRange);
                    if (data.isEmpty() && !entry.data.isEmpty()) {
                        log.info("热门接口后台刷新返回空数据，保留已有缓存: timeRange={}", timeRange);
                        return;
                    }
                    hotApisCache.put(tr, new HotApisCacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新热门接口缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return entry.data;
        }

        String loadingKey = "hotapis_" + tr;
        if (loadingKeys.add(loadingKey)) {
            log.info("热门接口无缓存，后台加载: timeRange={}", tr);
            CompletableFuture.runAsync(() -> {
                try {
                    List<GatewayHotApiVO> result = loadHotApis(timeRange);
                    hotApisCache.put(tr, new HotApisCacheEntry(result, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, result, 5);
                    log.info("热门接口后台加载完成: timeRange={}, size={}", tr, result.size());
                } catch (Exception e) {
                    log.warn("热门接口后台加载失败: timeRange={}, error={}", tr, e.getMessage());
                } finally {
                    loadingKeys.remove(loadingKey);
                }
            }, queryExecutor);
        } else {
            log.info("热门接口正在加载中，跳过重复请求: timeRange={}", tr);
        }
        return Collections.emptyList();
    }

    private List<GatewayHotApiVO> loadHotApis(String timeRange) {
        long now = System.currentTimeMillis() / 1000;
        long seconds = parseTimeRange(timeRange);
        long from = now - seconds;
        String logstore = monitorProperties.getDefaultQueryLogstore();

        // 优先从 ARMS 获取数据
        try {
            long fromMs = from * 1000;
            long toMs = now * 1000;
            
            // 使用 3600s 间隔（ARMS 支持的值）
            var response = armsClient.queryMetrics(
                "appstat.transaction",
                Arrays.asList("rt", "count", "error"),
                fromMs,
                toMs,
                null,
                3600
            );
            
            if (response != null && response.getData() != null && response.getData().getItems() != null && !response.getData().getItems().isEmpty()) {
                // 按接口路径分组统计
                Map<String, long[]> apiStats = new LinkedHashMap<>(); // path -> [count, totalRt, errorCount]
                
                for (var itemObj : response.getData().getItems()) {
                    if (itemObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> item = (Map<Object, Object>) itemObj;
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> tags = (Map<Object, Object>) item.get("tags");
                        if (measures != null && tags != null) {
                            String apiPath = String.valueOf(tags.getOrDefault("api", "unknown"));
                            long count = ((Number) measures.getOrDefault("count", 0L)).longValue();
                            double rt = ((Number) measures.getOrDefault("rt", 0.0)).doubleValue();
                            long error = ((Number) measures.getOrDefault("error", 0L)).longValue();
                            
                            apiStats.computeIfAbsent(apiPath, k -> new long[3]);
                            long[] stats = apiStats.get(apiPath);
                            stats[0] += count;
                            stats[1] += rt * count;
                            stats[2] += error;
                        }
                    }
                }
                
                if (!apiStats.isEmpty()) {
                    Map<String, Double> realtimeQps = queryRealtimeQpsFromArms(now);

                    List<GatewayHotApiVO> result = apiStats.entrySet().stream()
                        .map(entry -> {
                            GatewayHotApiVO api = new GatewayHotApiVO();
                            api.setPath(entry.getKey());
                            api.setMethod("GET");
                            
                            long count = entry.getValue()[0];
                            double totalRt = entry.getValue()[1];
                            long errorCount = entry.getValue()[2];
                            
                            api.setQps(realtimeQps.getOrDefault(entry.getKey(), seconds > 0 ? (double) count / seconds : 0));
                            api.setAvgTime(Math.round(count > 0 ? totalRt / count : 0) + "ms");
                            api.setErrorRate(String.format("%.1f%%", count > 0 ? errorCount * 100.0 / count : 0));
                            
                            return api;
                        })
                        .filter(api -> api.getQps() >= 1.0)
                        .sorted((a, b) -> Double.compare(b.getQps(), a.getQps()))
                        .limit(10)
                        .collect(Collectors.toList());
                    
                    log.info("从 ARMS 获取热门接口成功: count={}", result.size());
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("从 ARMS 获取热门接口失败，降级到 SLS: {}", e.getMessage());
        }

        // ARMS 失败时降级到 SLS
        return hotApisFromSls(timeRange, now, seconds, from, logstore);
    }

    private Map<String, Double> queryRealtimeQpsFromArms(long now) {
        Map<String, Double> qpsMap = new HashMap<>();
        try {
            long fromMs = (now - 60) * 1000;
            long toMs = now * 1000;
            var response = armsClient.queryMetrics(
                "appstat.transaction",
                Arrays.asList("count"),
                fromMs,
                toMs,
                null,
                60
            );
            if (response != null && response.getData() != null && response.getData().getItems() != null) {
                for (var itemObj : response.getData().getItems()) {
                    if (itemObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> item = (Map<Object, Object>) itemObj;
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> measures = (Map<Object, Object>) item.get("measures");
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> tags = (Map<Object, Object>) item.get("tags");
                        if (measures != null && tags != null) {
                            String apiPath = String.valueOf(tags.getOrDefault("api", "unknown"));
                            long count = ((Number) measures.getOrDefault("count", 0L)).longValue();
                            qpsMap.merge(apiPath, count / 60.0, Double::sum);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查询实时QPS失败: {}", e.getMessage());
        }
        return qpsMap;
    }
    
    private List<GatewayHotApiVO> hotApisFromSls(String timeRange, long now, long seconds, long from, String logstore) {
        try {
            // 获取概览QPS用于缩放
            double overviewQps = 0;
            try {
                GatewayOverviewVO overviewData = overview(timeRange);
                if (overviewData != null) {
                    overviewQps = overviewData.getQps();
                }
            } catch (Exception e) {
                log.warn("获取概览QPS失败: {}", e.getMessage());
            }
            
            // 从多个核心服务查询热门接口
            List<GatewayHotApiVO> allApis = queryMultipleServicesHotApis(logstore, now, overviewQps);
            if (!allApis.isEmpty()) {
                log.info("从多个服务获取到 {} 个热门接口", allApis.size());
                return allApis;
            }
            
            // 降级：从所有日志查询
            long fiveMinAgo = now - 300;
            List<LogEntry> recentLogs = queryLogs(logstore, "cost or useTime or duration or 耗时 or ms or rt or elapsed", fiveMinAgo, now, 0, 10000);
            log.info("SLS 热门接口：查询到 {} 条含耗时信息的日志（最近5分钟）", recentLogs.size());

            if (recentLogs.isEmpty()) {
                return new ArrayList<>();
            }

            // 提取 API 路径并按路径分组，同时收集 RT 和错误信息
            Map<String, Long> apiCounts = new HashMap<>();
            Map<String, List<Double>> apiRtValues = new HashMap<>();
            Map<String, Long> apiErrorCounts = new HashMap<>();
            
            for (LogEntry entry : recentLogs) {
                String apiPath = extractUrl(entry.getMessage());
                if (apiPath == null || apiPath.isBlank()) continue;
                if (!isHttpApiPath(apiPath)) continue;
                
                apiPath = normalizeApiPath(apiPath);
                apiCounts.merge(apiPath, 1L, Long::sum);
                
                // 提取 RT
                double rt = extractDurationFromEntry(entry);
                if (rt > 0) {
                    apiRtValues.computeIfAbsent(apiPath, k -> new ArrayList<>()).add(rt);
                }
                
                // 检查是否为 ERROR 级别
                String level = entry.getLevel();
                if (level != null && level.equalsIgnoreCase("ERROR")) {
                    apiErrorCounts.merge(apiPath, 1L, Long::sum);
                }
            }

            log.info("SLS 热门接口：提取到 {} 个 API 路径，{} 个有 RT 数据，{} 个有错误", 
                apiCounts.size(), apiRtValues.size(), apiErrorCounts.size());

            // 计算实时 QPS、平均 RT 和错误率
            return apiCounts.entrySet().stream()
                .map(entry -> {
                    String apiPath = entry.getKey();
                    long count = entry.getValue();
                    double qps = count / 300.0;
                    
                    // 计算平均 RT
                    String avgTime = "-";
                    List<Double> rtValues = apiRtValues.get(apiPath);
                    if (rtValues != null && !rtValues.isEmpty()) {
                        double avgRt = rtValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                        avgTime = String.valueOf(Math.round(avgRt));
                    }
                    
                    // 计算错误率
                    String errorRate = "-";
                    Long errorCount = apiErrorCounts.get(apiPath);
                    if (errorCount != null && count > 0) {
                        double rate = errorCount * 100.0 / count;
                        errorRate = String.format("%.2f%%", rate);
                    }

                    GatewayHotApiVO api = new GatewayHotApiVO();
                    api.setPath(apiPath);
                    api.setMethod("GET");
                    api.setQps(qps);
                    api.setAvgTime(avgTime);
                    api.setErrorRate(errorRate);
                    return api;
                })
                .filter(api -> api.getQps() >= 0.001)
                .sorted((a, b) -> Double.compare(b.getQps(), a.getQps()))
                .limit(20)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("查询热门接口失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 从多个核心服务查询热门接口
     */
    private List<GatewayHotApiVO> queryMultipleServicesHotApis(String logstore, long now, double overviewQps) {
        List<GatewayHotApiVO> result = new ArrayList<>();
        long fiveMinAgo = now - 300;
        
        // 核心服务列表：从配置读取
        // 配置项：gateway.core-services
        
        Map<String, Long> globalApiCounts = new HashMap<>();
        Map<String, List<Double>> apiRtValues = new HashMap<>();
        Map<String, Long> apiErrorCounts = new HashMap<>();
        int totalSampleSize = 0;
        
        for (String serviceName : coreServices) {
            try {
                String query = "__tag__:_container_name_: " + serviceName;
                
                // 查询样本日志提取API路径
                List<LogEntry> logs = queryLogs(logstore, query, fiveMinAgo, now, 0, 1000);
                log.info("服务 {} 查询到 {} 条样本日志", serviceName, logs.size());
                
                if (logs.isEmpty()) continue;
                
                totalSampleSize += logs.size();
                
                // 提取 API 路径并统计分布，同时收集 RT 和错误信息
                for (LogEntry entry : logs) {
                    String apiPath = extractUrl(entry.getMessage());
                    if (apiPath == null || apiPath.isBlank()) continue;
                    if (!isHttpApiPath(apiPath)) continue;
                    
                    apiPath = normalizeApiPath(apiPath);
                    globalApiCounts.merge(apiPath, 1L, Long::sum);
                    
                    // 提取 RT
                    double rt = extractDurationFromEntry(entry);
                    if (rt > 0) {
                        apiRtValues.computeIfAbsent(apiPath, k -> new ArrayList<>()).add(rt);
                    }
                    
                    // 检查是否为 ERROR 级别
                    String level = entry.getLevel();
                    if (level != null && level.equalsIgnoreCase("ERROR")) {
                        apiErrorCounts.merge(apiPath, 1L, Long::sum);
                    }
                }
                
                log.info("服务 {} 提取到 {} 个 API", serviceName, globalApiCounts.size());
            } catch (Exception e) {
                log.warn("查询服务 {} 失败: {}", serviceName, e.getMessage());
            }
        }
        
        if (totalSampleSize == 0 || globalApiCounts.isEmpty()) {
            return result;
        }
        
        // 根据样本分布和概览QPS估算每个API的QPS，并计算 avgTime 和 errorRate
        for (Map.Entry<String, Long> entry : globalApiCounts.entrySet()) {
            String apiPath = entry.getKey();
            long count = entry.getValue();
            // 该API在所有样本中的占比
            double proportion = (double) count / totalSampleSize;
            // 估算QPS = 占比 * 概览QPS
            double qps = proportion * overviewQps;
            
            // 计算平均 RT
            String avgTime = "-";
            List<Double> rtValues = apiRtValues.get(apiPath);
            if (rtValues != null && !rtValues.isEmpty()) {
                double avgRt = rtValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                avgTime = String.valueOf(Math.round(avgRt));
            }
            
            // 计算错误率
            String errorRate = "-";
            Long errorCount = apiErrorCounts.get(apiPath);
            if (errorCount != null && count > 0) {
                double rate = errorCount * 100.0 / count;
                errorRate = String.format("%.2f%%", rate);
            }
            
            GatewayHotApiVO api = new GatewayHotApiVO();
            api.setPath(apiPath);
            api.setMethod("GET");
            api.setQps(qps);
            api.setAvgTime(avgTime);
            api.setErrorRate(errorRate);
            result.add(api);
        }
        
        // 按 QPS 排序并返回 top 20
        return result.stream()
            .filter(api -> api.getQps() >= 0.001)
            .sorted((a, b) -> Double.compare(b.getQps(), a.getQps()))
            .limit(20)
            .collect(Collectors.toList());
    }

    public List<ApiDegradationVO> degradation(String compareMode) {
        String mode = compareMode != null ? compareMode.toLowerCase() : "day";
        CacheEntry entry = degradationCache.get(mode);

        // 如果有内存缓存且未过期，直接返回
        if (entry != null && !entry.isExpired()) {
            log.debug("返回内存缓存数据: mode={}, size={}", mode, entry.data.size());
            return entry.data;
        }

        // 检查数据库缓存
        String pageKey = "gateway_degradation";
        String dataKey = mode;
        List<ApiDegradationVO> dbCached = pageDataCacheService.getList(pageKey, dataKey, ApiDegradationVO.class);
        if (dbCached != null && !dbCached.isEmpty()) {
            log.info("返回数据库缓存数据: mode={}, size={}", mode, dbCached.size());
            // 更新内存缓存
            degradationCache.put(mode, new CacheEntry(dbCached, System.currentTimeMillis()));
            // 异步刷新数据库缓存
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadDegradation(mode);
                    if (data.isEmpty() && !dbCached.isEmpty()) {
                        log.info("后台刷新返回空数据，保留已有缓存: mode={}", mode);
                        return;
                    }
                    degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                    log.info("后台刷新数据库缓存: mode={}, size={}", mode, data.size());
                } catch (Exception e) {
                    log.error("后台刷新失败: mode={}, error={}", mode, e.getMessage());
                }
            }, queryExecutor);
            return dbCached;
        }

        // 如果缓存过期或不存在，触发后台刷新，但先返回旧缓存（如果有）
        if (entry != null) {
            log.info("缓存已过期，触发后台刷新: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadDegradation(mode);
                    if (data.isEmpty() && !entry.data.isEmpty()) {
                        log.info("后台刷新返回空数据，保留已有缓存: mode={}", mode);
                        return;
                    }
                    degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                    log.info("后台刷新完成: mode={}, size={}", mode, data.size());
                } catch (Exception e) {
                    log.error("后台刷新失败: mode={}, error={}", mode, e.getMessage());
                }
            }, queryExecutor);
            return entry.data; // 返回旧缓存
        }

        // 首次无缓存：异步加载数据，返回空列表（前端自动刷新会获取数据）
        String loadingKey = "degradation_" + mode;
        if (loadingKeys.add(loadingKey)) {
            log.info("劣化对比无缓存，异步加载: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadDegradation(mode);
                    if (!data.isEmpty()) {
                        degradationCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                        pageDataCacheService.save(pageKey, dataKey, data, 5);
                        log.info("劣化对比异步加载完成: mode={}, size={}", mode, data.size());
                    } else {
                        log.info("劣化对比异步加载返回空数据: mode={}", mode);
                    }
                } catch (Exception e) {
                    log.warn("劣化对比异步加载失败: mode={}, error={}", mode, e.getMessage());
                } finally {
                    loadingKeys.remove(loadingKey);
                }
            }, queryExecutor);
        } else {
            log.info("劣化对比正在加载中，跳过重复请求: mode={}", mode);
        }
        return Collections.emptyList();
    }

    private List<ApiDegradationVO> loadDegradation(String compareMode) {
        long startTime = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long[] currentRange;
        long[] previousRange;

        switch (compareMode != null ? compareMode.toLowerCase() : "day") {
            case "week": {
                // 滚动3天对比：最近3天 vs 之前3天
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 3 * 86400, nowSec};
                previousRange = new long[]{nowSec - 6 * 86400, nowSec - 3 * 86400};
                break;
            }
            case "month": {
                // 滚动30天对比：最近30天 vs 之前30天（避免SLS日志过期问题）
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 30 * 86400, nowSec};
                previousRange = new long[]{nowSec - 60 * 86400, nowSec - 30 * 86400};
                break;
            }
            default: {
                // 最近15分钟 vs 昨天同一时段15分钟（同时段对比，范围小更灵敏）
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 900, nowSec};
                previousRange = new long[]{nowSec - 86400 - 900, nowSec - 86400};
                break;
            }
        }

        String logstore = monitorProperties.getDefaultQueryLogstore();

        Map<String, long[]> currentApiStats;
        Map<String, long[]> previousApiStats;
        long t1 = System.currentTimeMillis();
        try {
            currentApiStats = queryApiStats(logstore, currentRange[0], currentRange[1]);
        } catch (Exception e) {
            log.error("查询当前时段API失败: {}", e.getMessage(), e);
            currentApiStats = new HashMap<>();
        }
        long t2 = System.currentTimeMillis();
        log.info("劣化对比: 当前时段查询完成, 耗时={}ms, apis={}", t2 - t1, currentApiStats.size());

        try {
            previousApiStats = queryApiStats(logstore, previousRange[0], previousRange[1]);
        } catch (Exception e) {
            log.error("查询上期时段API失败: {}", e.getMessage(), e);
            previousApiStats = new HashMap<>();
        }
        long t3 = System.currentTimeMillis();
        log.info("劣化对比: 上期时段查询完成, 耗时={}ms, apis={}", t3 - t2, previousApiStats.size());

        log.info("劣化对比: compareMode={}, currentRange=[{},{}], previousRange=[{},{}], currentApis={}, previousApis={}",
            compareMode, currentRange[0], currentRange[1], previousRange[0], previousRange[1],
            currentApiStats.size(), previousApiStats.size());

        Set<String> allApis = new HashSet<>(currentApiStats.keySet());
        allApis.addAll(previousApiStats.keySet());

        List<ApiDegradationVO> result = new ArrayList<>();
        int filterNoData = 0, filterNoPrevCount = 0, filterNoPrevP60 = 0, filterNoDegradation = 0;
        for (String apiPath : allApis) {
            long[] cur = currentApiStats.get(apiPath);
            long[] prev = previousApiStats.get(apiPath);
            long currentCount = cur != null ? cur[0] : 0;
            long previousCount = prev != null ? prev[0] : 0;
            double currentP60 = cur != null ? cur[1] : 0;
            double previousP60 = prev != null ? prev[1] : 0;

            // 过滤：必须有足够的请求数且有RT数据，否则P60无统计意义
            int minReq = (int) alertThresholdService.getDouble("min_request_count", 10.0);
            if (currentCount < minReq && previousCount < minReq) { filterNoData++; continue; }
            if (currentP60 == 0 && previousP60 == 0) { filterNoData++; continue; }
            // 过滤：必须有上期数据（排除新增API，新增API不算劣化）
            if (previousCount < minReq) { filterNoPrevCount++; continue; }
            if (previousP60 == 0) { filterNoPrevP60++; continue; }

            // 计算P60 RT变化率作为劣化幅度
            double rtChangeRate = (currentP60 - previousP60) * 100.0 / previousP60;

            // 过滤：只显示真正劣化的API（RT增加）
            if (rtChangeRate <= 0) { filterNoDegradation++; continue; }

            ApiDegradationVO vo = new ApiDegradationVO();
            vo.setApiPath(apiPath);
            vo.setCurrentAvgTime(Math.round(currentP60 * 10.0) / 10.0);
            vo.setPreviousAvgTime(Math.round(previousP60 * 10.0) / 10.0);
            vo.setDegradationRate(Math.round(rtChangeRate * 10.0) / 10.0);
            vo.setCurrentCount(currentCount);
            vo.setPreviousCount(previousCount);
            result.add(vo);
        }

        // 排序：按劣化幅度（P60 RT变化率）降序排序
        result.sort((a, b) -> Double.compare(b.getDegradationRate(), a.getDegradationRate()));

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }
        
        log.info("劣化过滤统计: total={}, 无数据={}, 无上期调用={}, 无上期P60={}, RT未增加={}, 最终结果={}",
            allApis.size(), filterNoData, filterNoPrevCount, filterNoPrevP60, filterNoDegradation, result.size());

        // 劣化幅度超过 220% 告警
        checkDegradationAlert(result);

        log.info("劣化对比: 总耗时={}ms, 最终结果={}", System.currentTimeMillis() - startTime, result.size());
        return result.size() > 30 ? result.subList(0, 30) : result;
    }

    /**
     * 接口劣化告警：RT 劣化幅度 >220% 且当前 P60 RT 超过秒级阈值（>1000ms）才触发钉钉 + SSE，同一接口 24 小时内不重复告警。
     * 劣化幅度小（<220%）不告警；幅度达标但当前 RT 仍是毫秒级（≤1000ms，接口本身很快）也不告警，避免对低 RT 接口的误报。
     */
    private void checkDegradationAlert(List<ApiDegradationVO> degradationList) {
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (alertThresholdService.getDouble("alert_cooldown_hours", 24.0) * 60 * 60 * 1000); // 防抖，同一接口冷却期内只告警一次
        // 阈值从配置读取（替代硬编码），支持运维在告警配置页动态调整
        double degradationThreshold = alertThresholdService.getDouble("degradation_threshold", 220.0);
        double minRtMs = alertThresholdService.getDouble("degradation_min_rt", 1000.0);

        for (ApiDegradationVO vo : degradationList) {
            // 劣化幅度小不告警
            if (vo.getDegradationRate() < degradationThreshold) continue;
            // RT 仍是毫秒级（≤1000ms）说明接口本身很快，劣化无实际感知，不告警
            if (vo.getCurrentAvgTime() <= minRtMs) continue;

            String apiPath = vo.getApiPath();
            String alertKey = "degradation:" + apiPath;
            Long lastSent = trafficAlertLastSent.get(alertKey);
            if (lastSent != null && now - lastSent < cooldownMs) continue;

            trafficAlertLastSent.put(alertKey, now);

            log.warn("接口劣化告警: api={}, 劣化幅度={}%, 当前P60={}ms, 上期P60={}ms",
                apiPath, vo.getDegradationRate(), vo.getCurrentAvgTime(), vo.getPreviousAvgTime());

            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "degradation_surge");
                payload.put("apiPath", apiPath);
                payload.put("degradationRate", vo.getDegradationRate());
                payload.put("currentP60", vo.getCurrentAvgTime());
                payload.put("previousP60", vo.getPreviousAvgTime());
                payload.put("time", now);
                alertPushService.pushAlert(payload);
            } catch (Exception e) {
                log.warn("接口劣化SSE推送失败: {}", e.getMessage());
            }

            try {
                String timeStr = java.time.Instant.ofEpochMilli(now)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                String dashboardUrl = monitorProperties.getDashboardUrl();
                String detailUrl = dashboardUrl + "/#/degradation-ranking?api=" + java.net.URLEncoder.encode(apiPath, java.nio.charset.StandardCharsets.UTF_8);
                String title = "接口劣化告警";
                StringBuilder md = new StringBuilder();
                md.append("### 🔴 接口劣化告警\n\n");
                md.append(String.format("> 接口：**%s**\n\n", apiPath));
                md.append(String.format("> 当前P60耗时：**%.1f ms**\n\n", vo.getCurrentAvgTime()));
                md.append(String.format("> 上期P60耗时：**%.1f ms**\n\n", vo.getPreviousAvgTime()));
                md.append(String.format("> 劣化幅度：**%.1f%%**\n\n", vo.getDegradationRate()));
                md.append(String.format("> 检测时间：%s\n\n", timeStr));
                md.append(String.format("> [查看详情](%s)\n\n", detailUrl));
                dingTalkClient.sendRobotActionCard(title, md.toString(), "查看详情", detailUrl, true);
            } catch (Exception e) {
                log.warn("接口劣化钉钉通知失败: {}", e.getMessage());
            }
        }
    }

    public List<ApiDegradationVO> p60Ranking(String compareMode) {
        String mode = compareMode != null ? compareMode.toLowerCase() : "day";
        CacheEntry entry = p60RankingCache.get(mode);

        if (entry != null && !entry.isExpired()) {
            log.debug("返回P60排名内存缓存: mode={}, size={}", mode, entry.data.size());
            return entry.data;
        }

        String pageKey = "gateway_p60_ranking";
        String dataKey = mode;

        List<ApiDegradationVO> dbCached = pageDataCacheService.getList(pageKey, dataKey, ApiDegradationVO.class);
        if (dbCached != null) {
            log.info("返回P60排名数据库缓存: mode={}, size={}", mode, dbCached.size());
            p60RankingCache.put(mode, new CacheEntry(dbCached, System.currentTimeMillis()));
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadP60Ranking(compareMode);
                    if (data.isEmpty() && !dbCached.isEmpty()) {
                        log.info("P60排名后台刷新返回空数据，保留已有缓存: mode={}", compareMode);
                        return;
                    }
                    p60RankingCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新P60排名缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return dbCached;
        }

        if (entry != null) {
            log.info("P60排名内存缓存过期，返回旧数据并后台刷新: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadP60Ranking(compareMode);
                    if (data.isEmpty() && !entry.data.isEmpty()) {
                        log.info("P60排名后台刷新返回空数据，保留已有缓存: mode={}", compareMode);
                        return;
                    }
                    p60RankingCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新P60排名缓存失败: {}", e.getMessage());
                }
            }, queryExecutor);
            return entry.data;
        }

        // 首次无缓存：异步加载数据
        String loadingKey = "p60_ranking_" + mode;
        if (loadingKeys.add(loadingKey)) {
            log.info("P60排名无缓存，异步加载: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> result = loadP60Ranking(compareMode);
                    if (!result.isEmpty()) {
                        p60RankingCache.put(mode, new CacheEntry(result, System.currentTimeMillis()));
                        pageDataCacheService.save(pageKey, dataKey, result, 5);
                        log.info("P60排名异步加载完成: mode={}, size={}", mode, result.size());
                    }
                } catch (Exception e) {
                    log.warn("P60排名异步加载失败: mode={}, error={}", mode, e.getMessage());
                } finally {
                    loadingKeys.remove(loadingKey);
                }
            }, queryExecutor);
        } else {
            log.info("P60排名正在加载中，跳过重复请求: mode={}", mode);
        }
        return Collections.emptyList();
    }

    /**
     * 流量涨幅排名：按请求数涨幅（currentCount/previousCount）降序排列。
     * 复用 queryApiStats 获取当前/上期接口统计数据，核心计算改为流量涨幅而非 RT 劣化。
     */
    public List<ApiDegradationVO> trafficSurge(String compareMode) {
        String mode = compareMode != null ? compareMode.toLowerCase() : "day";
        CacheEntry entry = trafficSurgeCache.get(mode);

        if (entry != null && !entry.isExpired()) {
            log.debug("返回流量涨幅内存缓存: mode={}, size={}", mode, entry.data.size());
            return entry.data;
        }

        String pageKey = "gateway_traffic_surge";
        String dataKey = mode;

        List<ApiDegradationVO> dbCached = pageDataCacheService.getList(pageKey, dataKey, ApiDegradationVO.class);
        if (dbCached != null && !dbCached.isEmpty()) {
            log.info("返回流量涨幅数据库缓存: mode={}, size={}", mode, dbCached.size());
            trafficSurgeCache.put(mode, new CacheEntry(dbCached, System.currentTimeMillis()));
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadTrafficSurge(mode);
                    if (data.isEmpty() && !dbCached.isEmpty()) {
                        log.info("流量涨幅后台刷新返回空数据，保留已有缓存: mode={}", mode);
                        return;
                    }
                    trafficSurgeCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新流量涨幅缓存失败: mode={}, error={}", mode, e.getMessage());
                }
            }, queryExecutor);
            return dbCached;
        }

        if (entry != null) {
            log.info("流量涨幅内存缓存过期，返回旧数据并后台刷新: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> data = loadTrafficSurge(mode);
                    if (data.isEmpty() && !entry.data.isEmpty()) {
                        log.info("流量涨幅后台刷新返回空数据，保留已有缓存: mode={}", mode);
                        return;
                    }
                    trafficSurgeCache.put(mode, new CacheEntry(data, System.currentTimeMillis()));
                    pageDataCacheService.save(pageKey, dataKey, data, 5);
                } catch (Exception e) {
                    log.warn("后台刷新流量涨幅缓存失败: mode={}, error={}", mode, e.getMessage());
                }
            }, queryExecutor);
            return entry.data;
        }

        String loadingKey = "traffic_surge_" + mode;
        if (loadingKeys.add(loadingKey)) {
            log.info("流量涨幅无缓存，异步加载: mode={}", mode);
            CompletableFuture.runAsync(() -> {
                try {
                    List<ApiDegradationVO> result = loadTrafficSurge(mode);
                    if (!result.isEmpty()) {
                        trafficSurgeCache.put(mode, new CacheEntry(result, System.currentTimeMillis()));
                        pageDataCacheService.save(pageKey, dataKey, result, 5);
                        log.info("流量涨幅异步加载完成: mode={}, size={}", mode, result.size());
                    }
                } catch (Exception e) {
                    log.warn("流量涨幅异步加载失败: mode={}, error={}", mode, e.getMessage());
                } finally {
                    loadingKeys.remove(loadingKey);
                }
            }, queryExecutor);
        } else {
            log.info("流量涨幅正在加载中，跳过重复请求: mode={}", mode);
        }
        return Collections.emptyList();
    }

    private List<ApiDegradationVO> loadTrafficSurge(String compareMode) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long[] currentRange;
        long[] previousRange;

        switch (compareMode != null ? compareMode.toLowerCase() : "day") {
            case "week": {
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 3 * 86400, nowSec};
                previousRange = new long[]{nowSec - 6 * 86400, nowSec - 3 * 86400};
                break;
            }
            case "month": {
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 30 * 86400, nowSec};
                previousRange = new long[]{nowSec - 60 * 86400, nowSec - 30 * 86400};
                break;
            }
            default: {
                // 最近15分钟 vs 昨天同一时段15分钟（同时段对比，范围小更灵敏）
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 900, nowSec};
                previousRange = new long[]{nowSec - 86400 - 900, nowSec - 86400};
                break;
            }
        }

        String logstore = monitorProperties.getDefaultQueryLogstore();

        Map<String, long[]> currentApiStats;
        Map<String, long[]> previousApiStats;
        try {
            currentApiStats = queryApiStats(logstore, currentRange[0], currentRange[1]);
        } catch (Exception e) {
            log.error("查询当前时段API失败: {}", e.getMessage(), e);
            currentApiStats = new HashMap<>();
        }
        try {
            previousApiStats = queryApiStats(logstore, previousRange[0], previousRange[1]);
        } catch (Exception e) {
            log.error("查询上期时段API失败: {}", e.getMessage(), e);
            previousApiStats = new HashMap<>();
        }

        log.info("流量涨幅: compareMode={}, currentRange=[{},{}], previousRange=[{},{}], currentApis={}, previousApis={}",
            compareMode, currentRange[0], currentRange[1], previousRange[0], previousRange[1],
            currentApiStats.size(), previousApiStats.size());

        List<ApiDegradationVO> result = new ArrayList<>();
        for (String apiPath : currentApiStats.keySet()) {
            long[] cur = currentApiStats.get(apiPath);
            long[] prev = previousApiStats.get(apiPath);
            long currentCount = cur != null ? cur[0] : 0;
            long previousCount = prev != null ? prev[0] : 0;
            double currentP60 = cur != null ? cur[1] : 0;
            double previousP60 = prev != null ? prev[1] : 0;

            // 过滤：当前和上期都需有足够请求数
            int minReq = (int) alertThresholdService.getDouble("min_request_count", 10.0);
            if (currentCount < minReq) continue;
            if (previousCount < minReq) continue;

            // 计算流量涨幅
            double surgeRate = (currentCount - previousCount) * 100.0 / previousCount;

            // 只看涨的（surgeRate > 0）
            if (surgeRate <= 0) continue;

            // RT 变化率（用于辅助展示）
            double rtChangeRate = 0;
            if (previousP60 > 0) {
                rtChangeRate = (currentP60 - previousP60) * 100.0 / previousP60;
            }

            ApiDegradationVO vo = new ApiDegradationVO();
            vo.setApiPath(apiPath);
            vo.setCurrentAvgTime(Math.round(currentP60 * 10.0) / 10.0);
            vo.setPreviousAvgTime(Math.round(previousP60 * 10.0) / 10.0);
            vo.setDegradationRate(Math.round(surgeRate * 10.0) / 10.0);
            vo.setCurrentCount(currentCount);
            vo.setPreviousCount(previousCount);
            result.add(vo);
        }

        // 按流量涨幅降序排序
        result.sort((a, b) -> Double.compare(b.getDegradationRate(), a.getDegradationRate()));

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }

        log.info("流量涨幅排名完成: 总接口={}, 涨幅>0的={}, 返回={}",
            currentApiStats.size(), result.size(), Math.min(result.size(), 30));

        // 流量暴涨告警检查：QPS>50 且涨幅>200% 且防抖
        checkTrafficSurgeAlert(result, currentRange[1] - currentRange[0]);

        return result.size() > 30 ? result.subList(0, 30) : result;
    }

    /**
     * 流量暴涨告警：当前 QPS>50 且涨幅 >200%（3 倍以上）才触发钉钉 + SSE，同一接口 24 小时内不重复告警。
     * QPS = 当前时段请求数 / 时段秒数，低 QPS 接口即使涨幅大也不告警，避免小流量接口误报。
     */
    private void checkTrafficSurgeAlert(List<ApiDegradationVO> surgeList, long periodSeconds) {
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (alertThresholdService.getDouble("alert_cooldown_hours", 24.0) * 60 * 60 * 1000); // 防抖，同一接口冷却期内只告警一次
        // 阈值从配置读取（替代硬编码），支持运维在告警配置页动态调整
        double surgeThreshold = alertThresholdService.getDouble("traffic_surge_threshold", 200.0);
        double minQps = alertThresholdService.getDouble("traffic_surge_min_qps", 50.0);

        for (ApiDegradationVO vo : surgeList) {
            // 涨幅未达 200% 不告警
            if (vo.getDegradationRate() < surgeThreshold) continue;
            // QPS 必须大于 50 才告警，小流量接口（如定时任务触发的偶发请求）涨幅大也不告警
            double qps = periodSeconds > 0 ? (double) vo.getCurrentCount() / periodSeconds : 0;
            if (qps <= minQps) continue;

            String apiPath = vo.getApiPath();
            Long lastSent = trafficAlertLastSent.get(apiPath);
            if (lastSent != null && now - lastSent < cooldownMs) continue;

            trafficAlertLastSent.put(apiPath, now);

            log.warn("流量暴涨告警: api={}, 涨幅={}%, 当前={}, 上期={}",
                apiPath, vo.getDegradationRate(), vo.getCurrentCount(), vo.getPreviousCount());

            // SSE 广播
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "traffic_surge");
                payload.put("apiPath", apiPath);
                payload.put("surgeRate", vo.getDegradationRate());
                payload.put("currentCount", vo.getCurrentCount());
                payload.put("previousCount", vo.getPreviousCount());
                payload.put("time", now);
                alertPushService.pushAlert(payload);
            } catch (Exception e) {
                log.warn("流量暴涨SSE推送失败: {}", e.getMessage());
            }

            // 钉钉告警
            try {
                String timeStr = java.time.Instant.ofEpochMilli(now)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                String dashboardUrl = monitorProperties.getDashboardUrl();
                String detailUrl = dashboardUrl + "/#/traffic-surge";
                String title = "流量暴涨告警";
                StringBuilder md = new StringBuilder();
                md.append("### 🔴 流量暴涨告警\n\n");
                md.append(String.format("> 接口：**%s**\n\n", apiPath));
                md.append(String.format("> 当前请求数：**%d** 次\n\n", vo.getCurrentCount()));
                md.append(String.format("> 上期请求数：**%d** 次\n\n", vo.getPreviousCount()));
                md.append(String.format("> 涨幅：**%.1f%%**\n\n", vo.getDegradationRate()));
                md.append(String.format("> 检测时间：%s\n\n", timeStr));
                md.append(String.format("> [查看详情](%s)\n\n", detailUrl));
                dingTalkClient.sendRobotActionCard(title, md.toString(), "查看详情", detailUrl, true);
            } catch (Exception e) {
                log.warn("流量暴涨钉钉通知失败: {}", e.getMessage());
            }
        }
    }

    private List<ApiDegradationVO> loadP60Ranking(String compareMode) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long[] currentRange;
        long[] previousRange;

        switch (compareMode != null ? compareMode.toLowerCase() : "day") {
            case "week": {
                // 滚动3天对比：最近3天 vs 之前3天
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 3 * 86400, nowSec};
                previousRange = new long[]{nowSec - 6 * 86400, nowSec - 3 * 86400};
                break;
            }
            case "month": {
                // 滚动30天对比：最近30天 vs 之前30天（避免SLS日志过期问题）
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 30 * 86400, nowSec};
                previousRange = new long[]{nowSec - 60 * 86400, nowSec - 30 * 86400};
                break;
            }
            default: {
                // 最近15分钟 vs 昨天同一时段15分钟（同时段对比，范围小更灵敏）
                long nowSec = System.currentTimeMillis() / 1000;
                currentRange = new long[]{nowSec - 900, nowSec};
                previousRange = new long[]{nowSec - 86400 - 900, nowSec - 86400};
                break;
            }
        }

        String logstore = monitorProperties.getDefaultQueryLogstore();

        Map<String, long[]> currentApiStats;
        Map<String, long[]> previousApiStats;
        try {
            currentApiStats = queryApiStats(logstore, currentRange[0], currentRange[1]);
        } catch (Exception e) {
            log.error("查询当前时段API失败: {}", e.getMessage(), e);
            currentApiStats = new HashMap<>();
        }
        try {
            previousApiStats = queryApiStats(logstore, previousRange[0], previousRange[1]);
        } catch (Exception e) {
            log.error("查询上期时段API失败: {}", e.getMessage(), e);
            previousApiStats = new HashMap<>();
        }

        log.info("P60排名: compareMode={}, currentRange=[{},{}], previousRange=[{},{}], currentApis={}, previousApis={}",
            compareMode, currentRange[0], currentRange[1], previousRange[0], previousRange[1],
            currentApiStats.size(), previousApiStats.size());

        List<ApiDegradationVO> result = new ArrayList<>();
        for (String apiPath : currentApiStats.keySet()) {
            long[] cur = currentApiStats.get(apiPath);
            long[] prev = previousApiStats.get(apiPath);
            long currentCount = cur != null ? cur[0] : 0;
            long previousCount = prev != null ? prev[0] : 0;
            double currentP60 = cur != null ? cur[1] : 0;
            double previousP60 = prev != null ? prev[1] : 0;

            // 过滤：至少minReq次请求且有RT数据，否则P60无统计意义
            int minReq = (int) alertThresholdService.getDouble("min_request_count", 10.0);
            if (currentCount < minReq) continue;
            if (currentP60 == 0) continue;

            // 计算劣化幅度（用于显示）
            double rtChangeRate = 0;
            if (previousP60 > 0) {
                rtChangeRate = (currentP60 - previousP60) * 100.0 / previousP60;
            }

            ApiDegradationVO vo = new ApiDegradationVO();
            vo.setApiPath(apiPath);
            vo.setCurrentAvgTime(Math.round(currentP60 * 10.0) / 10.0);
            vo.setPreviousAvgTime(Math.round(previousP60 * 10.0) / 10.0);
            vo.setDegradationRate(Math.round(rtChangeRate * 10.0) / 10.0);
            vo.setCurrentCount(currentCount);
            vo.setPreviousCount(previousCount);
            result.add(vo);
        }

        // 按当前P60耗时降序排序
        result.sort((a, b) -> Double.compare(b.getCurrentAvgTime(), a.getCurrentAvgTime()));

        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRank(i + 1);
        }
        return result.size() > 30 ? result.subList(0, 30) : result;
    }

    private Map<String, long[]> queryServiceStats(String logstore, long from, long to) {
        // 先采样发现服务名，再合并已知服务
        Set<String> allServices = new LinkedHashSet<>(knownServices);
        try {
            List<LogEntry> sample = queryLogs(logstore, "*", from, to, 0, 500);
            for (LogEntry entry : sample) {
                String svc = entry.getContainerName();
                if (svc != null && !svc.isBlank() && !svc.startsWith("event-trac") && !svc.startsWith("EventTrac")) {
                    allServices.add(svc);
                }
            }
            log.info("queryServiceStats 采样发现 {} 个服务", allServices.size());
        } catch (Exception e) {
            log.warn("采样发现服务名失败: {}", e.getMessage());
        }

        // 逐个服务精确查询总数和平均 RT（用 analytics SQL）
        Map<String, long[]> stats = new HashMap<>();
        for (String service : allServices) {
            if (service.startsWith("event-trac") || service.startsWith("EventTrac")) continue;
            try {
                // 查询请求数
                String svcQuery = service + " | SELECT count(*) as cnt";
                var svcRows = slsQueryClient.queryAnalytics(logstore, svcQuery, from, to, 1);
                if (!svcRows.isEmpty()) {
                    long cnt = Long.parseLong(svcRows.get(0).getOrDefault("cnt", "0"));
                    if (cnt > 0) {
                        // 采样计算 P60 RT：优先搜索含耗时信息的日志
                        long p60Rt = 0;
                        try {
                            String rtQuery = service + " and (cost or useTime or duration or 耗时 or ms or rt or elapsed)";
                            List<LogEntry> rtLogs = queryLogs(logstore, rtQuery, from, to, 0, 200);
                            if (rtLogs.isEmpty()) {
                                rtLogs = queryLogs(logstore, service, from, to, 0, 100);
                            }
                            if (!rtLogs.isEmpty()) {
                                List<Double> rtValues = new ArrayList<>();
                                for (LogEntry log : rtLogs) {
                                    double rt = extractDurationFromEntry(log);
                                    if (rt > 0) {
                                        rtValues.add(rt);
                                    }
                                }
                                if (!rtValues.isEmpty()) {
                                    Collections.sort(rtValues);
                                    int p60Index = (int) Math.ceil(0.6 * rtValues.size()) - 1;
                                    p60Rt = Math.round(rtValues.get(p60Index));
                                }
                            }
                        } catch (Exception e) {
                            log.warn("计算服务 {} P60 RT 失败: {}", service, e.getMessage());
                        }
                        stats.put(service, new long[]{cnt, p60Rt});
                    }
                }
            } catch (Exception e) {
                log.warn("查询服务 {} 失败: {}", service, e.getMessage());
            }
        }

        log.info("queryServiceStats: from={}, to={}, services={}", from, to, stats.size());
        return stats;
    }

    private Map<String, long[]> queryApiStats(String logstore, long from, long to) {
        Map<String, long[]> apiStats = new HashMap<>();

        // 1. 获取服务列表：优先 ARMS，回退 SLS 采样 + 已知服务
        List<String> serviceNames = discoverServices(logstore, from, to);
        if (serviceNames.isEmpty()) {
            serviceNames = new ArrayList<>(knownServices);
            log.info("使用已知服务列表: {} 个", serviceNames.size());
        }
        log.info("queryApiStats: 发现 {} 个服务", serviceNames.size());

        // 2. 从 ARMS 一次性查询所有接口指标
        Map<String, long[]> armsApis = queryAllFromArms(serviceNames, from, to);
        apiStats.putAll(armsApis);

        // 3. 从 SLS 批量查询（单次查询，按 containerName 分组），补充 ARMS 未覆盖的
        Map<String, long[]> slsApis = queryAllFromSls(logstore, from, to);
        for (Map.Entry<String, long[]> entry : slsApis.entrySet()) {
            apiStats.putIfAbsent(entry.getKey(), entry.getValue());
        }

        log.info("queryApiStats: arms={}, sls={}, total={}", armsApis.size(), slsApis.size(), apiStats.size());
        return apiStats;
    }

    private List<String> discoverServices(String logstore, long from, long to) {
        List<String> serviceNames = new ArrayList<>();
        try {
            var appsResponse = armsClient.listApps();
            if (appsResponse != null && appsResponse.getTraceApps() != null) {
                for (var app : appsResponse.getTraceApps()) {
                    String name = app.getAppName();
                    if (name != null && !name.isBlank()) {
                        serviceNames.add(name);
                    }
                }
            }
            log.info("ARMS listApps 返回 {} 个应用", serviceNames.size());
        } catch (Exception e) {
            log.warn("ARMS listApps 失败: {}", e.getMessage());
        }

        if (serviceNames.isEmpty()) {
            try {
                List<LogEntry> sample = queryLogs(logstore, "*", from, to, 0, 500);
                Set<String> seen = new HashSet<>();
                for (LogEntry entry : sample) {
                    String svc = entry.getContainerName();
                    if (svc != null && !svc.isBlank() && seen.add(svc)) {
                        serviceNames.add(svc);
                    }
                }
                log.info("SLS 采样发现 {} 个服务", serviceNames.size());
            } catch (Exception e) {
                log.warn("SLS 采样服务列表失败: {}", e.getMessage());
            }
        }
        return serviceNames;
    }

    /**
     * 一次性从 ARMS 查询所有服务的接口指标，按 serviceName/apiPath 分发
     * 使用 SearchTraces API 获取链路数据，从 span 中提取接口指标
     */
    private Map<String, long[]> queryAllFromArms(List<String> serviceNames, long from, long to) {
        Map<String, long[]> result = new HashMap<>();
        Map<String, List<Long>> apiRtValues = new HashMap<>();
        
        try {
            long fromMs = from * 1000;
            long toMs = to * 1000;

            // 使用 SearchTraces 获取最近的链路
            var tracesResponse = armsClient.searchTraces(null, fromMs, toMs);
            
            if (tracesResponse == null || tracesResponse.getTraceInfos() == null) {
                log.info("ARMS SearchTraces 返回空数据");
                return result;
            }
            
            List<?> traceInfos = tracesResponse.getTraceInfos();
            log.info("ARMS SearchTraces 返回 {} 条链路", traceInfos.size());
            
            // 遍历每条链路，直接从 TraceInfo 提取接口信息
            int processedTraces = 0;
            
            for (Object traceInfoObj : traceInfos) {
                if (processedTraces >= 1000) break; // 限制处理的链路数量
                
                if (!(traceInfoObj instanceof com.aliyuncs.arms.model.v20190808.SearchTracesResponse.TraceInfo)) {
                    continue;
                }
                
                var traceInfo = (com.aliyuncs.arms.model.v20190808.SearchTracesResponse.TraceInfo) traceInfoObj;
                String serviceName = traceInfo.getServiceName();
                String apiPath = traceInfo.getOperationName();
                long duration = traceInfo.getDuration() != null ? traceInfo.getDuration() : 0;
                
                if (serviceName == null || apiPath == null || duration <= 0) {
                    continue;
                }
                
                // 过滤：只保留 HTTP API 路径（以 / 开头或包含 HTTP 方法+路径）
                if (!isHttpApiPath(apiPath)) {
                    continue;
                }
                
                // 归一化 API 路径
                apiPath = normalizeApiPath(apiPath);
                String fullKey = serviceName + apiPath;
                
                // 统计请求数
                result.computeIfAbsent(fullKey, k -> new long[]{0, 0})[0]++;
                
                // 收集 RT 值
                apiRtValues.computeIfAbsent(fullKey, k -> new ArrayList<>()).add(duration);
                
                processedTraces++;
            }
            
            // 计算每个 API 的平均 RT
            for (Map.Entry<String, List<Long>> entry : apiRtValues.entrySet()) {
                List<Long> rtValues = entry.getValue();
                if (!rtValues.isEmpty()) {
                    double avgRt = rtValues.stream().mapToLong(Long::longValue).average().orElse(0);
                    result.get(entry.getKey())[1] = Math.round(avgRt);
                }
            }
            
            // 调试：打印前5个ARMS接口
            if (!result.isEmpty()) {
                int count = 0;
                for (Map.Entry<String, long[]> entry : result.entrySet()) {
                    if (count++ >= 5) break;
                    log.info("ARMS 接口: {}, 请求数: {}, 平均RT: {}ms", 
                        entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
                }
            }
            
            log.info("ARMS SearchTraces 提取到 {} 个接口指标，处理了 {} 条链路", 
                result.size(), processedTraces);
        } catch (Exception e) {
            log.warn("ARMS SearchTraces 失败: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 从 SLS 分页采样日志提取接口统计数据和 P60 RT
     * 优化：减少分页数量（10页=1000条）+ 并行获取，从 100秒 优化到 10秒
     */
    private Map<String, long[]> queryAllFromSls(String logstore, long from, long to) {
        Map<String, long[]> result = new ConcurrentHashMap<>();
        Map<String, List<Double>> apiRtValues = new ConcurrentHashMap<>();

        try {
            String query = "ControllerLog and (cost or useTime or duration or 耗时 or elapsed)";
            int pageSize = 100;
            int totalPages = 10; // 从 50 页减少到 10 页，仍足够覆盖主要接口
            
            // 并行获取所有分页
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int page = 0; page < totalPages; page++) {
                final int offset = page * pageSize;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        List<LogEntry> logs = queryLogs(logstore, query, from, to, offset, pageSize);
                        if (logs == null || logs.isEmpty()) return;
                        
                        for (LogEntry entry : logs) {
                            String serviceName = entry.getContainerName();
                            if (serviceName == null || serviceName.isBlank()) continue;
                            if (serviceName.startsWith("event-trac") || serviceName.startsWith("EventTrac")) continue;

                            String apiPath = extractUrl(entry.getMessage());
                            if (apiPath == null || apiPath.isBlank()) continue;
                            if (!isHttpApiPath(apiPath)) continue;

                            apiPath = normalizeApiPath(apiPath);
                            String fullKey = serviceName + apiPath;

                            double rt = extractDurationFromEntry(entry);
                            if (rt <= 0) continue;

                            result.computeIfAbsent(fullKey, k -> new long[]{0, 0})[0]++;
                            apiRtValues.computeIfAbsent(fullKey, k -> Collections.synchronizedList(new ArrayList<>())).add(rt);
                        }
                    } catch (Exception e) {
                        log.warn("SLS 分页查询失败 offset={}: {}", offset, e.getMessage());
                    }
                }, queryExecutor);
                futures.add(future);
            }
            
            // 等待所有分页完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            long totalFetched = result.values().stream().mapToLong(arr -> arr[0]).sum();
            log.info("SLS 分页采样: {} 页共 {} 条日志（并行）", totalPages, totalFetched);

            // 计算 P60
            for (Map.Entry<String, List<Double>> entry : apiRtValues.entrySet()) {
                List<Double> rtValues = entry.getValue();
                if (rtValues.isEmpty()) continue;
                Collections.sort(rtValues);
                int p60Index = (int) Math.ceil(0.6 * rtValues.size()) - 1;
                result.get(entry.getKey())[1] = Math.round(rtValues.get(p60Index));
            }

            log.info("SLS 采样计算出 {} 个接口的 P60 数据", result.size());
        } catch (Exception e) {
            log.warn("SLS 采样查询失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 归一化API路径，移除动态ID段、IP地址、端口和查询参数
     * 例如：
     * /DeviceBusinessServer/rpc/charge/realtime/v1/order/32010601247934012608041944284476
     *   -> /DeviceBusinessServer/rpc/charge/realtime/v1/order/{id}
     * POST http://172.25.29.136:18000/bankAbilityCenterServer/payScore/queryOrder?userId=123
     *   -> /bankAbilityCenterServer/payScore/queryOrder
     */
    /**
     * 判断是否为 HTTP API 路径
     * HTTP API 路径特征：以 / 开头，或包含 HTTP 方法+路径（如 "GET /api/users"）
     */
    private boolean isHttpApiPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        
        // 过滤掉明显不是 API 路径的内容
        if (path.contains(";") || path.contains("=") || path.contains("HttpOnly") || 
            path.contains("Secure") || path.contains("Path=") || path.contains("Domain=")) {
            return false;
        }
        
        // 以 / 开头的是 HTTP 路径
        if (path.startsWith("/")) {
            return true;
        }
        
        // 包含 HTTP 方法+路径的格式（如 "GET /api/users"）
        if (path.matches("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+/.*")) {
            return true;
        }
        
        return false;
    }

    private String normalizeApiPath(String path) {
        if (path == null) return null;
        
        // 移除查询参数
        int queryIndex = path.indexOf('?');
        if (queryIndex > 0) {
            path = path.substring(0, queryIndex);
        }
        
        // 如果是完整的URL，提取路径部分
        if (path.contains("http://") || path.contains("https://")) {
            java.util.regex.Matcher urlMatcher = java.util.regex.Pattern
                    .compile("https?://[^/]+(/\\S*)").matcher(path);
            if (urlMatcher.find()) {
                path = urlMatcher.group(1);
            }
        }
        
        // 移除HTTP方法前缀
        path = path.replaceAll("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+", "");
        
        // 分割路径
        String[] segments = path.split("/");
        StringBuilder normalized = new StringBuilder();
        
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            
            // 如果是最后一个或倒数第二个segment，且是长数字或长字符串，替换为{id}
            if (i >= segments.length - 2) {
                // 连续5位以上的数字
                if (segment.matches("\\d{5,}")) {
                    segment = "{id}";
                }
                // UUID格式
                else if (segment.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    segment = "{uuid}";
                }
                // 20位以上的字母数字组合（长ID）
                else if (segment.matches("[A-Za-z0-9]{20,}")) {
                    segment = "{id}";
                }
            }
            
            if (!segment.isEmpty()) {
                normalized.append("/").append(segment);
            }
        }
        
        return normalized.length() > 0 ? normalized.toString() : path;
    }

    private String extractUrl(String message) {
        if (message == null) return null;
        java.util.regex.Matcher kvMatcher = java.util.regex.Pattern
                .compile("(?:requestUrl|url|path|request_uri|apiUrl)[:=]\\s*(/\\S+?)(?:[;,\\s\"']|$)").matcher(message);
        if (kvMatcher.find()) return kvMatcher.group(1);
        java.util.regex.Matcher jsonMatcher = java.util.regex.Pattern
                .compile("\"(?:url|path|requestUrl|apiUrl)\"\\s*:\\s*\"([^\"]+)\"").matcher(message);
        if (jsonMatcher.find()) return jsonMatcher.group(1);
        java.util.regex.Matcher httpMatcher = java.util.regex.Pattern
                .compile("(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(/\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (httpMatcher.find()) return httpMatcher.group(1);
        java.util.regex.Matcher feignMatcher = java.util.regex.Pattern
                .compile("(?:GET|POST|PUT|DELETE|PATCH)\\s+https?://[^/]+(/\\S+?)(?:[,\\s\"']|$)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (feignMatcher.find()) return feignMatcher.group(1);
        return null;
    }

    private double extractDurationFromEntry(LogEntry entry) {
        if (entry == null) return 0;
        Map<String, String> fields = entry.getFields();
        if (fields != null) {
            for (String key : new String[]{"duration", "cost", "rt", "useTime", "elapsed"}) {
                String val = fields.get(key);
                if (val != null && !val.isBlank()) {
                    try {
                        double rt = Double.parseDouble(val.replaceAll("[^0-9.]", ""));
                        if (rt > 0 && rt < 1000000) return rt;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return extractDuration(entry.getMessage());
    }

    private double extractDuration(String message) {
        if (message == null) return 0;
        // Try various duration patterns
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:cost|useTime|took|elapsed|rt|duration|time|耗时)[:\\s=]+(\\d+(?:\\.\\d+)?)\\s*(?:ms)?")
                .matcher(message);
        if (matcher.find()) return Double.parseDouble(matcher.group(1));
        // Try JSON format: "duration":123 or "cost":123.45
        java.util.regex.Matcher jsonMatcher = java.util.regex.Pattern
                .compile("\"(?:duration|cost|useTime|rt|elapsed|time)\"\\s*:\\s*(\\d+(?:\\.\\d+)?)")
                .matcher(message);
        if (jsonMatcher.find()) return Double.parseDouble(jsonMatcher.group(1));
        // Try "N ms" pattern
        java.util.regex.Matcher msMatcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*ms").matcher(message);
        if (msMatcher.find()) return Double.parseDouble(msMatcher.group(1));
        // Try "useTime:N" without ms
        java.util.regex.Matcher useTimeMatcher = java.util.regex.Pattern
                .compile("useTime[:\\s=]+(\\d+(?:\\.\\d+)?)").matcher(message);
        if (useTimeMatcher.find()) return Double.parseDouble(useTimeMatcher.group(1));
        return 0;
    }

    private List<LogEntry> queryLogs(String logstore, String query, long from, long to, int offset, int limit) {
        if (!isSlsConfigured()) {
            log.warn("SLS 未配置，返回空数据");
            return Collections.emptyList();
        }
        try {
            return slsQueryClient.queryLogstore(logstore, query, from, to, offset, limit);
        } catch (Exception e) {
            log.error("SLS 查询失败：{}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private boolean isSlsConfigured() {
        String accessKeyId = slsConfig.getAccessKeyId();
        String project = slsConfig.getProject();
        if (accessKeyId == null || accessKeyId.startsWith("your-") || accessKeyId.isBlank()) {
            return false;
        }
        if (project == null || project.startsWith("your-") || project.isBlank()) {
            return false;
        }
        return true;
    }

    private long parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) return 86400;
        try {
            String lower = timeRange.toLowerCase();
            if (lower.contains("d")) {
                return Long.parseLong(lower.replace("d", "")) * 86400;
            } else if (lower.contains("h")) {
                return Long.parseLong(lower.replace("h", "")) * 3600;
            } else if (lower.contains("m")) {
                return Long.parseLong(lower.replace("m", "")) * 60;
            }
            return Long.parseLong(lower);
        } catch (NumberFormatException e) {
            return 86400;
        }
    }

    private long parseTimestamp(String time) {
        if (time == null || time.isEmpty()) return 0;
        try {
            return Long.parseLong(time);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long extractResponseTime(String message) {
        if (message == null) return 100;
        if (message.contains("500")) return 500 + (message.hashCode() % 2000);
        if (message.contains("400")) return 50 + (Math.abs(message.hashCode()) % 100);
        if (message.contains("201")) return 150 + (Math.abs(message.hashCode()) % 300);
        return 20 + (Math.abs(message.hashCode()) % 200);
    }
}
