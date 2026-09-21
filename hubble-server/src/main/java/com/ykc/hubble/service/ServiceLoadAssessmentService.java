package com.ykc.hubble.service;

import com.ykc.hubble.entity.ServiceLoadDaily;
import com.ykc.hubble.mapper.ServiceLoadDailyMapper;
import com.ykc.hubble.vo.ServiceAssessmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 服务扩容评估：峰值水位 + 7天环比 + 线性外推触顶预测 + 建议分级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceLoadAssessmentService {

    private final ServiceLoadDailyMapper serviceLoadDailyMapper;

    @Value("${service-load.assessment.cpu-yellow:80}")
    double cpuYellow = 80;
    @Value("${service-load.assessment.cpu-red:90}")
    double cpuRed = 90;
    @Value("${service-load.assessment.mem-yellow:85}")
    double memYellow = 85;
    @Value("${service-load.assessment.mem-red:95}")
    double memRed = 95;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public Map<String, Object> assessAll(int days) {
        LocalDate startDate = LocalDate.now(ZONE).minusDays(days);
        List<ServiceLoadDaily> all = serviceLoadDailyMapper.selectAllByDateRange(startDate);

        Map<String, List<ServiceLoadDaily>> byApp = new TreeMap<>();
        if (all != null) {
            for (ServiceLoadDaily r : all) {
                if (r.getAppName() == null) continue;
                byApp.computeIfAbsent(r.getAppName(), k -> new ArrayList<>()).add(r);
            }
        }

        List<ServiceAssessmentVO> items = new ArrayList<>();
        for (Map.Entry<String, List<ServiceLoadDaily>> e : byApp.entrySet()) {
            e.getValue().sort(Comparator.comparing(ServiceLoadDaily::getStatDate));
            items.add(assess(e.getKey(), e.getValue()));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("urgent", countByLevel(items, "URGENT"));
        summary.put("suggest", countByLevel(items, "SUGGEST"));
        summary.put("watch", countByLevel(items, "WATCH"));
        summary.put("normal", countByLevel(items, "NORMAL"));
        summary.put("insufficient", countByLevel(items, "INSUFFICIENT"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("summary", summary);
        result.put("generatedAt", LocalDateTime.now(ZONE).toString());
        return result;
    }

    public ServiceAssessmentVO assess(String appName, List<ServiceLoadDaily> records) {
        ServiceAssessmentVO vo = new ServiceAssessmentVO();
        vo.setAppName(appName);
        vo.setPartialToday(0);

        // 1. 预处理：剔除全空记录
        List<ServiceLoadDaily> valid = new ArrayList<>();
        for (ServiceLoadDaily r : records) {
            if (r == null) continue;
            boolean hasData = r.getMaxCpu() != null || r.getMaxMemory() != null
                    || (r.getTotalCount() != null && r.getTotalCount() > 0);
            if (hasData) valid.add(r);
        }
        if (valid.isEmpty()) {
            return insufficient(vo, 0, "窗口内无有效数据");
        }

        // 最新记录（可能为半天数据，仅作当前水位展示）
        ServiceLoadDaily latest = valid.get(valid.size() - 1);
        if (latest.getStatDate() != null) {
            vo.setLatestStatDate(latest.getStatDate().toString());
            vo.setLatestDataAgeDays((int) ChronoUnit.DAYS.between(latest.getStatDate(), LocalDate.now(ZONE)));
        }
        if (LocalDate.now(ZONE).equals(latest.getStatDate())
                && latest.getPartialDay() != null && latest.getPartialDay() == 1) {
            vo.setPartialToday(1);
        }

        // 2. 半天数据排除出回归与峰值
        List<ServiceLoadDaily> full = new ArrayList<>();
        for (ServiceLoadDaily r : valid) {
            if (r.getPartialDay() == null || r.getPartialDay() != 1) full.add(r);
        }
        if (full.isEmpty()) {
            return insufficient(vo, 0, "窗口内仅有半天数据");
        }

        vo.setSampleCount(full.size());
        long span = ChronoUnit.DAYS.between(full.get(0).getStatDate(),
                full.get(full.size() - 1).getStatDate()) + 1;
        vo.setCoverageDays((int) span);
        vo.setCompleteness((int) Math.round(100.0 * full.size() / span));

        // 3. 峰值水位
        vo.setCpuPeak(maxOf(full, ServiceLoadDaily::getMaxCpu));
        vo.setMemPeak(maxOf(full, ServiceLoadDaily::getMaxMemory));
        vo.setQpsPeak(maxOf(full, ServiceLoadDaily::getMaxQps));
        vo.setCpuWaterLevel(waterLevel(vo.getCpuPeak(), cpuYellow, cpuRed));
        vo.setMemWaterLevel(waterLevel(vo.getMemPeak(), memYellow, memRed));

        // 4. 环比（末7个完整样本 vs 前7个）
        vo.setCpuGrowthPct(growthPct(full, ServiceLoadDaily::getAvgCpu));
        vo.setMemGrowthPct(growthPct(full, ServiceLoadDaily::getAvgMemory));
        vo.setQpsGrowthPct(growthPct(full, ServiceLoadDaily::getMaxQps));

        // 5. 线性回归 + 触顶预测
        Regression cpuReg = regress(full, ServiceLoadDaily::getMaxCpu);
        Regression memReg = regress(full, ServiceLoadDaily::getMaxMemory);
        vo.setCpuSlopePerDay(slopeOf(cpuReg));
        vo.setMemSlopePerDay(slopeOf(memReg));

        Integer cpuDays = predictDays(cpuReg, cpuRed);
        Integer memDays = predictDays(memReg, memRed);
        vo.setCpuDaysToThreshold(cpuDays);
        vo.setMemDaysToThreshold(memDays);
        resolvePrediction(vo, cpuReg, memReg, cpuDays, memDays);

        resolveAdvice(vo, cpuDays, memDays);
        return vo;
    }

    private BigDecimal slopeOf(Regression reg) {
        return reg == null ? null : BigDecimal.valueOf(reg.slope).setScale(3, RoundingMode.HALF_UP);
    }

    private ServiceAssessmentVO insufficient(ServiceAssessmentVO vo, int sampleCount, String reason) {
        vo.setSampleCount(sampleCount);
        vo.setPrediction("数据不足");
        vo.setConfidence("LOW");
        vo.setAdviceLevel("INSUFFICIENT");
        vo.setAdviceText("数据不足，无法评估");
        vo.setInsufficientReason(reason);
        return vo;
    }

    private void resolvePrediction(ServiceAssessmentVO vo, Regression cpuReg, Regression memReg,
                                   Integer cpuDays, Integer memDays) {
        Integer headline = null;
        String driver = null;
        if (cpuDays != null && memDays != null) {
            if (cpuDays <= memDays) { headline = cpuDays; driver = "cpu"; }
            else { headline = memDays; driver = "mem"; }
        } else if (cpuDays != null) {
            headline = cpuDays; driver = "cpu";
        } else if (memDays != null) {
            headline = memDays; driver = "mem";
        }

        if (headline != null) {
            String driverName = "cpu".equals(driver) ? "CPU" : "内存";
            vo.setPrediction(headline == 0 ? driverName + "已达阈值" : "≈" + headline + "天后" + driverName + "触顶");
            vo.setConfidence(confidenceOf("cpu".equals(driver) ? cpuReg : memReg));
            return;
        }

        boolean hasRegression = (cpuReg != null && cpuReg.n >= 7) || (memReg != null && memReg.n >= 7);
        if (!hasRegression) {
            vo.setPrediction("数据不足");
            vo.setConfidence("LOW");
            return;
        }
        Regression better = betterOf(cpuReg, memReg);
        vo.setPrediction("平稳");
        vo.setConfidence(confidenceOf(better));
    }

    private void resolveAdvice(ServiceAssessmentVO vo, Integer cpuDays, Integer memDays) {
        if (vo.getSampleCount() < 7) {
            vo.setAdviceLevel("INSUFFICIENT");
            vo.setAdviceText("数据不足，无法评估");
            vo.setInsufficientReason("完整样本 " + vo.getSampleCount() + " 天（<7）");
            return;
        }

        Integer headline = null;
        String driverName = null;
        if (cpuDays != null && memDays != null) {
            headline = Math.min(cpuDays, memDays);
            driverName = cpuDays <= memDays ? "CPU" : "内存";
        } else if (cpuDays != null) {
            headline = cpuDays; driverName = "CPU";
        } else if (memDays != null) {
            headline = memDays; driverName = "内存";
        }

        // 从水位与预测收集扩容目标
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        List<String> redHits = new ArrayList<>();
        List<String> yellowHits = new ArrayList<>();
        if ("red".equals(vo.getCpuWaterLevel())) {
            targets.add("CPU"); redHits.add("CPU " + fmtPct(vo.getCpuPeak()) + "%");
        } else if ("yellow".equals(vo.getCpuWaterLevel())) {
            targets.add("CPU"); yellowHits.add("CPU " + fmtPct(vo.getCpuPeak()) + "%");
        }
        if ("red".equals(vo.getMemWaterLevel())) {
            targets.add("内存"); redHits.add("内存 " + fmtPct(vo.getMemPeak()) + "%");
        } else if ("yellow".equals(vo.getMemWaterLevel())) {
            targets.add("内存"); yellowHits.add("内存 " + fmtPct(vo.getMemPeak()) + "%");
        }

        boolean redHit = !redHits.isEmpty();
        boolean yellowHit = !yellowHits.isEmpty();
        boolean urgentPredict = headline != null && headline > 0 && headline <= 7
                && "HIGH".equals(vo.getConfidence());
        boolean growth30 = hit(vo.getCpuGrowthPct(), 30) || hit(vo.getMemGrowthPct(), 30)
                || hit(vo.getQpsGrowthPct(), 30);

        if (redHit) {
            vo.setAdviceLevel("URGENT");
            String text = String.join("、", redHits) + " 已达红线，建议立即扩容";
            if (!yellowHits.isEmpty()) {
                text += "；" + String.join("、", yellowHits) + " 已达黄线，建议一并规划";
            }
            vo.setAdviceText(text);
        } else if (urgentPredict) {
            vo.setAdviceLevel("URGENT");
            targets.add(driverName);
            vo.setAdviceText("预计 " + headline + " 天内 " + driverName + " 触顶（高置信），建议尽快扩容 " + driverName);
        } else if (yellowHit) {
            vo.setAdviceLevel("SUGGEST");
            vo.setAdviceText(String.join("、", yellowHits) + " 已达黄线，建议规划扩容");
        } else if (headline != null && headline <= 14) {
            vo.setAdviceLevel("SUGGEST");
            targets.add(driverName);
            vo.setAdviceText("预计 " + headline + " 天后 " + driverName + " 触顶，建议提前规划扩容 " + driverName);
        } else if ((headline != null && headline <= 30) || growth30) {
            vo.setAdviceLevel("WATCH");
            if (headline != null) {
                targets.add(driverName);
                vo.setAdviceText("预计 " + headline + " 天后 " + driverName + " 触顶，持续关注");
            } else {
                List<String> growthHits = new ArrayList<>();
                if (hit(vo.getCpuGrowthPct(), 30)) growthHits.add("CPU");
                if (hit(vo.getMemGrowthPct(), 30)) growthHits.add("内存");
                if (hit(vo.getQpsGrowthPct(), 30)) growthHits.add("QPS");
                targets.addAll(growthHits);
                vo.setAdviceText(String.join("、", growthHits) + "环比涨幅 ≥30%，持续关注");
            }
        } else {
            vo.setAdviceLevel("NORMAL");
            vo.setAdviceText("水位正常，趋势平稳");
        }
        vo.setAdviceTargets(targets.isEmpty() ? null : String.join("+", targets));
    }

    private String fmtPct(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private boolean hit(BigDecimal growthPct, double threshold) {
        return growthPct != null && growthPct.doubleValue() >= threshold;
    }

    private String confidenceOf(Regression reg) {
        if (reg == null) return "LOW";
        if (reg.n >= 14 && reg.r2 >= 0.6) return "HIGH";
        if (reg.r2 >= 0.3) return "MEDIUM";
        return "LOW";
    }

    private Regression betterOf(Regression a, Regression b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.r2 >= b.r2 ? a : b;
    }

    /**
     * slope>0.05%/天且未达阈值 → 天数(上限365)；已达标 → 0；否则 null(平稳)
     */
    private Integer predictDays(Regression reg, double threshold) {
        if (reg == null || reg.n < 7) return null;
        if (reg.lastValue >= threshold) return 0;
        if (reg.slope > 0.05) {
            long days = (long) Math.ceil((threshold - reg.lastValue) / reg.slope);
            return (int) Math.min(days, 365);
        }
        return null;
    }

    private Regression regress(List<ServiceLoadDaily> samples, Function<ServiceLoadDaily, BigDecimal> extractor) {
        List<double[]> pts = new ArrayList<>();
        for (ServiceLoadDaily r : samples) {
            BigDecimal v = extractor.apply(r);
            if (v == null) continue;
            pts.add(new double[]{r.getStatDate().toEpochDay(), v.doubleValue()});
        }
        if (pts.size() < 7) return null;

        double n = pts.size();
        double mx = 0, my = 0;
        for (double[] p : pts) { mx += p[0]; my += p[1]; }
        mx /= n; my /= n;

        double sxy = 0, sxx = 0, syy = 0;
        for (double[] p : pts) {
            double dx = p[0] - mx, dy = p[1] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }

        Regression reg = new Regression();
        reg.n = pts.size();
        reg.lastValue = pts.get(pts.size() - 1)[1];
        if (sxx == 0) {
            reg.slope = 0;
            reg.r2 = 0;
            return reg;
        }
        reg.slope = sxy / sxx;
        reg.intercept = my - reg.slope * mx;
        reg.r2 = syy == 0 ? 0 : Math.max(0, Math.min(1, (sxy * sxy) / (sxx * syy)));
        return reg;
    }

    /**
     * 末7个样本均值 vs 前7个样本均值；任一半非空样本 <5 → null
     */
    private BigDecimal growthPct(List<ServiceLoadDaily> full, Function<ServiceLoadDaily, BigDecimal> extractor) {
        int n = full.size();
        int start = Math.max(0, n - 7);
        List<Double> lastVals = new ArrayList<>();
        List<Double> prevVals = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal v = extractor.apply(full.get(i));
            if (v == null) continue;
            if (i >= start) lastVals.add(v.doubleValue());
            else prevVals.add(v.doubleValue());
        }
        if (lastVals.size() < 5 || prevVals.size() < 5) return null;

        double lastMean = mean(lastVals);
        double prevMean = mean(prevVals);
        if (prevMean <= 0) return null;
        return BigDecimal.valueOf((lastMean - prevMean) / prevMean * 100).setScale(1, RoundingMode.HALF_UP);
    }

    private double mean(List<Double> vals) {
        double sum = 0;
        for (double v : vals) sum += v;
        return sum / vals.size();
    }

    private BigDecimal maxOf(List<ServiceLoadDaily> samples, Function<ServiceLoadDaily, BigDecimal> extractor) {
        BigDecimal max = null;
        for (ServiceLoadDaily r : samples) {
            BigDecimal v = extractor.apply(r);
            if (v != null && (max == null || v.compareTo(max) > 0)) max = v;
        }
        return max;
    }

    private String waterLevel(BigDecimal peak, double yellow, double red) {
        if (peak == null) return null;
        double v = peak.doubleValue();
        if (v >= red) return "red";
        if (v >= yellow) return "yellow";
        return "green";
    }

    private int countByLevel(List<ServiceAssessmentVO> items, String level) {
        return (int) items.stream().filter(i -> level.equals(i.getAdviceLevel())).count();
    }

    private static class Regression {
        double slope;
        double intercept;
        double r2;
        int n;
        double lastValue;
    }
}
