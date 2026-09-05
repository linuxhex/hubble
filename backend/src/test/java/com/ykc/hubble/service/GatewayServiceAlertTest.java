package com.ykc.hubble.service;

import com.ykc.hubble.client.ArmsClient;
import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.client.SlsQueryClient;
import com.ykc.hubble.config.MonitorProperties;
import com.ykc.hubble.config.SlsConfig;
import com.ykc.hubble.vo.ApiDegradationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * GatewayService 接口劣化 / 流量暴涨告警条件回归测试。
 * 覆盖三个修复点：
 * 1. 劣化告警：幅度 >220% 且当前 P60 RT >100ms 才告警
 * 2. 流量暴涨告警：涨幅 >200% 且 QPS >50 才告警
 * 3. （时段对比逻辑在 loadXxx 内部，此处聚焦告警条件）
 */
class GatewayServiceAlertTest {

    private GatewayService gatewayService;
    private AlertPushService alertPushService;
    private DingTalkClient dingTalkClient;

    @BeforeEach
    void setUp() throws Exception {
        // mock 全部构造参数
        SlsQueryClient slsQueryClient = mock(SlsQueryClient.class);
        ArmsClient armsClient = mock(ArmsClient.class);
        MonitorProperties monitorProperties = mock(MonitorProperties.class);
        SlsConfig slsConfig = mock(SlsConfig.class);
        OverviewSnapshotCache overviewSnapshotCache = mock(OverviewSnapshotCache.class);
        PageDataCacheService pageDataCacheService = mock(PageDataCacheService.class);
        Executor queryExecutor = Runnable::run;
        alertPushService = mock(AlertPushService.class);
        dingTalkClient = mock(DingTalkClient.class);

        // 用反射构造，绕过 @Qualifier 字段注入限制
        @SuppressWarnings("unchecked")
        Constructor<GatewayService> ctor = (Constructor<GatewayService>) GatewayService.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        gatewayService = ctor.newInstance(
                slsQueryClient, armsClient, monitorProperties, slsConfig,
                overviewSnapshotCache, pageDataCacheService, queryExecutor,
                alertPushService, dingTalkClient);

        // 清空防抖 map，避免用例间干扰
        Field f = GatewayService.class.getDeclaredField("trafficAlertLastSent");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(gatewayService)).clear();
    }

    /** 反射调用 private checkDegradationAlert */
    private void invokeDegradationAlert(List<ApiDegradationVO> list) throws Exception {
        Method m = GatewayService.class.getDeclaredMethod("checkDegradationAlert", List.class);
        m.setAccessible(true);
        m.invoke(gatewayService, list);
    }

    /** 反射调用 private checkTrafficSurgeAlert(List, long) */
    private void invokeTrafficSurgeAlert(List<ApiDegradationVO> list, long periodSeconds) throws Exception {
        Method m = GatewayService.class.getDeclaredMethod("checkTrafficSurgeAlert", List.class, long.class);
        m.setAccessible(true);
        m.invoke(gatewayService, list, periodSeconds);
    }

    private ApiDegradationVO vo(String api, double degradeRate, double currentRt, long currentCount) {
        ApiDegradationVO v = new ApiDegradationVO();
        v.setApiPath(api);
        v.setDegradationRate(degradeRate);
        v.setCurrentAvgTime(currentRt);
        v.setPreviousAvgTime(currentRt / 3);
        v.setCurrentCount(currentCount);
        v.setPreviousCount(Math.max(1, currentCount / 3));
        return v;
    }

    // ==================== 接口劣化告警 ====================

    @Test
    @DisplayName("劣化幅度>220% 且 当前RT>100ms → 应告警")
    void degradation_highRate_highRt_shouldAlert() throws Exception {
        invokeDegradationAlert(List.of(vo("/api/a", 300.0, 200.0, 1000)));
        verify(alertPushService, times(1)).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("劣化幅度>220% 但 当前RT<=100ms → 不告警（低RT接口劣化无实际影响）")
    void degradation_highRate_lowRt_shouldNotAlert() throws Exception {
        invokeDegradationAlert(List.of(vo("/api/a", 300.0, 80.0, 1000)));
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("劣化幅度<220% → 不告警（幅度小不告警）")
    void degradation_lowRate_shouldNotAlert() throws Exception {
        invokeDegradationAlert(List.of(vo("/api/a", 100.0, 500.0, 1000)));
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("当前RT恰好100ms → 不告警（边界，必须 >100ms）")
    void degradation_boundaryRt100_shouldNotAlert() throws Exception {
        invokeDegradationAlert(List.of(vo("/api/a", 300.0, 100.0, 1000)));
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    // ==================== 流量暴涨告警 ====================

    @Test
    @DisplayName("涨幅>200% 且 QPS>50 → 应告警")
    void trafficSurge_highSurge_highQps_shouldAlert() throws Exception {
        // periodSeconds=100, currentCount=10000 → QPS=100 > 50
        invokeTrafficSurgeAlert(List.of(vo("/api/a", 300.0, 50.0, 10000)), 100L);
        verify(alertPushService, times(1)).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("涨幅>200% 但 QPS<=50 → 不告警（小流量接口涨幅大不告警）")
    void trafficSurge_highSurge_lowQps_shouldNotAlert() throws Exception {
        // periodSeconds=100, currentCount=3000 → QPS=30 <= 50
        invokeTrafficSurgeAlert(List.of(vo("/api/a", 300.0, 50.0, 3000)), 100L);
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("涨幅<200% → 不告警")
    void trafficSurge_lowSurge_shouldNotAlert() throws Exception {
        // 涨幅 100% < 200%
        invokeTrafficSurgeAlert(List.of(vo("/api/a", 100.0, 50.0, 10000)), 100L);
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("QPS恰好50 → 不告警（边界，必须 >50）")
    void trafficSurge_boundaryQps50_shouldNotAlert() throws Exception {
        // periodSeconds=100, currentCount=5000 → QPS=50.0 <= 50
        invokeTrafficSurgeAlert(List.of(vo("/api/a", 300.0, 50.0, 5000)), 100L);
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("periodSeconds=0 时不告警（避免除零，QPS 视为 0）")
    void trafficSurge_zeroPeriod_shouldNotAlert() throws Exception {
        invokeTrafficSurgeAlert(List.of(vo("/api/a", 300.0, 50.0, 10000)), 0L);
        verify(alertPushService, never()).pushAlert(org.mockito.ArgumentMatchers.anyMap());
    }
}
