package com.ykc.hubble.service;

import com.ykc.hubble.entity.ServiceLoadDaily;
import com.ykc.hubble.vo.ServiceAssessmentVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceLoadAssessmentTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ServiceLoadAssessmentService service = new ServiceLoadAssessmentService(null);

    private LocalDate day(int minusDays) {
        return LocalDate.now(ZONE).minusDays(minusDays);
    }

    private ServiceLoadDaily rec(LocalDate date, Double maxCpu, Double maxMem, Double maxQps) {
        ServiceLoadDaily r = new ServiceLoadDaily();
        r.setAppName("svc");
        r.setStatDate(date);
        r.setPartialDay(0);
        if (maxCpu != null) {
            r.setMaxCpu(bd(maxCpu));
            r.setAvgCpu(bd(maxCpu * 0.8));
        }
        if (maxMem != null) {
            r.setMaxMemory(bd(maxMem));
            r.setAvgMemory(bd(maxMem * 0.9));
        }
        if (maxQps != null) {
            r.setMaxQps(bd(maxQps));
            r.setTotalCount((long) (maxQps * 3600 * 8));
        }
        return r;
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 用例1：线性上升趋势 → 预测触顶 + 高置信 + 建议扩容
     * maxCpu = 40 + 1.2*i（每天+1.2），末值 74.8 → (90-74.8)/1.2 ≈ 12.7 → 13天
     */
    @Test
    void risingTrend_predictsDaysToThreshold_suggest() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            records.add(rec(day(29 - i), 40 + 1.2 * i, 60.0, 100.0));
        }
        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals(30, vo.getSampleCount());
        assertEquals(30, vo.getCoverageDays());
        assertEquals(100, vo.getCompleteness());
        assertEquals("green", vo.getCpuWaterLevel());
        assertEquals("HIGH", vo.getConfidence());
        assertEquals("≈13天后CPU触顶", vo.getPrediction());
        assertEquals(13, vo.getCpuDaysToThreshold());
        assertEquals("SUGGEST", vo.getAdviceLevel());
        assertEquals("CPU", vo.getAdviceTargets());
        assertNull(vo.getMemDaysToThreshold());
        assertEquals(0, vo.getPartialToday());
        assertNotNull(vo.getCpuGrowthPct());
    }

    /**
     * 用例2：下降趋势（峰值70 < 黄线80）→ 平稳 + NORMAL
     */
    @Test
    void fallingTrend_stable_normal() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            records.add(rec(day(29 - i), 70 - 0.5 * i, 50.0, 80.0));
        }
        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals("平稳", vo.getPrediction());
        assertEquals("HIGH", vo.getConfidence());
        assertNull(vo.getCpuDaysToThreshold());
        assertNull(vo.getMemDaysToThreshold());
        assertEquals("NORMAL", vo.getAdviceLevel());
        assertNull(vo.getAdviceTargets());
        assertEquals("green", vo.getCpuWaterLevel());
    }

    /**
     * 用例3：样本不足（5天）→ INSUFFICIENT
     */
    @Test
    void insufficientSamples_below7() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(rec(day(4 - i), 50.0, 60.0, 100.0));
        }
        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals(5, vo.getSampleCount());
        assertEquals("INSUFFICIENT", vo.getAdviceLevel());
        assertEquals("数据不足", vo.getPrediction());
        assertTrue(vo.getInsufficientReason().contains("5"));
    }

    /**
     * 用例4：隔天缺天 → 回归用真实 epochDay，斜率按自然日计算
     * 15个样本隔2天分布（跨度29天），maxCpu 每样本+0.5 → 每天+0.25，末值47 → (90-47)/0.25 = 172天
     */
    @Test
    void alternateDayGaps_regressionUsesEpochDay() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            records.add(rec(day(28 - 2 * i), 40 + 0.5 * i, 50.0, 100.0));
        }
        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals(15, vo.getSampleCount());
        assertEquals(29, vo.getCoverageDays());
        assertEquals(52, vo.getCompleteness());
        assertTrue(vo.getCpuDaysToThreshold() >= 160 && vo.getCpuDaysToThreshold() <= 184,
                "预测天数应≈172，实际=" + vo.getCpuDaysToThreshold());
        assertTrue(vo.getPrediction().startsWith("≈"));
        assertNotNull(vo.getCpuGrowthPct(), "平缓上升时环比应有值");
    }

    /**
     * 用例5：半天数据 → 排除出回归与峰值，仅标记 partialToday
     * 30天平稳数据(CPU=50) + 最后一天半天数据 CPU 飙到 99
     */
    @Test
    void partialDayExcludedFromPeakAndRegression() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            records.add(rec(day(30 - i), 50.0, 60.0, 100.0));
        }
        ServiceLoadDaily partial = rec(day(0), 99.0, 90.0, 500.0);
        partial.setPartialDay(1);
        records.add(partial);

        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals(1, vo.getPartialToday());
        assertEquals(30, vo.getSampleCount());
        assertEquals(50.00, vo.getCpuPeak().doubleValue(), 0.01);
        assertEquals(60.00, vo.getMemPeak().doubleValue(), 0.01);
        assertEquals(100.00, vo.getQpsPeak().doubleValue(), 0.01);
        assertEquals("green", vo.getCpuWaterLevel());
        assertEquals("NORMAL", vo.getAdviceLevel());
    }

    /**
     * 用例6：全空记录剔除 + 环比样本不足时为 null 而非 0
     * 20天窗口中前半段 CPU 非空样本仅4个(<5) → cpuGrowthPct=null；内存全满额 → 环比=0
     */
    @Test
    void allNullDroppedAndGrowthNullNotZero() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int daysAgo = 19 - i;
            Double cpu = (daysAgo <= 6 || daysAgo >= 16) ? 50.0 + i * 0.2 : null;
            records.add(rec(day(daysAgo), cpu, 60.0, 100.0));
        }
        // 1条全空记录（无CPU无内存无流量）
        ServiceLoadDaily empty = new ServiceLoadDaily();
        empty.setStatDate(day(20));
        records.add(empty);

        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals(20, vo.getSampleCount());
        assertEquals(20, vo.getCoverageDays());
        assertEquals(100, vo.getCompleteness());
        assertNull(vo.getCpuGrowthPct(), "前半段CPU样本<5，环比应为null而非0");
        assertEquals(0.0, vo.getMemGrowthPct().doubleValue(), 0.1);
    }

    /**
     * 用例7：恰好达红线阈值 → days=0、已达阈值、水位红、URGENT
     */
    @Test
    void exactlyAtThreshold_urgentZeroDays() {
        List<ServiceLoadDaily> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(rec(day(19 - i), 50.0 + i * 2.1, 60.0, 100.0));
        }
        // 末条恰为红线 90
        records.set(records.size() - 1, rec(day(0), 90.0, 60.0, 100.0));

        ServiceAssessmentVO vo = service.assess("svc", records);

        assertEquals(0, vo.getCpuDaysToThreshold());
        assertEquals("CPU已达阈值", vo.getPrediction());
        assertEquals("red", vo.getCpuWaterLevel());
        assertEquals("URGENT", vo.getAdviceLevel());
        assertEquals("CPU", vo.getAdviceTargets());
        assertTrue(vo.getAdviceText().contains("CPU 90%"));
    }
}
