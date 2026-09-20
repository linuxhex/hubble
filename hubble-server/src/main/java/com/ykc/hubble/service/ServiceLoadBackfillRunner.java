package com.ykc.hubble.service;

import com.ykc.hubble.entity.ServiceLoadDaily;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动补采：查找最近 N 天缺天日期，从旧到新逐日补采；连续 2 天采集为空即熔断
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceLoadBackfillRunner {

    private final ServiceLoadService serviceLoadService;

    @Value("${service-load.backfill-on-startup:true}")
    private boolean backfillOnStartup;

    @Value("${service-load.backfill-days:14}")
    private int backfillDays;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!backfillOnStartup) {
            log.info("服务负载启动补采已关闭(service-load.backfill-on-startup=false)");
            return;
        }
        Thread thread = new Thread(this::runBackfillSafely, "service-load-backfill");
        thread.setDaemon(true);
        thread.start();
    }

    private void runBackfillSafely() {
        if (!running.compareAndSet(false, true)) {
            log.info("服务负载补采已在执行，跳过本次触发");
            return;
        }
        try {
            runBackfill();
        } catch (Exception e) {
            log.error("服务负载启动补采异常终止", e);
        } finally {
            running.set(false);
        }
    }

    void runBackfill() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate startDate = today.minusDays(backfillDays);

        // 按日期统计应用数：仅判断“日期缺失”会让单应用缺天永不补采，
        // 需同时判断当日应用数是否明显少于完整日（阈值 90%）
        Map<LocalDate, Integer> countByDate = new HashMap<>();
        List<ServiceLoadDaily> rows = serviceLoadService.queryAllByDateRange(startDate);
        if (rows != null) {
            for (ServiceLoadDaily row : rows) {
                if (row.getStatDate() != null) {
                    countByDate.merge(row.getStatDate(), 1, Integer::sum);
                }
            }
        }
        int maxApps = countByDate.values().stream().max(Integer::compareTo).orElse(0);
        int minApps = Math.max(1, (int) Math.floor(maxApps * 0.9));

        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(today); d = d.plusDays(1)) {
            Integer c = countByDate.get(d);
            if (c == null || c < minApps) {
                missing.add(d);
            }
        }
        if (missing.isEmpty()) {
            log.info("服务负载启动补采: 最近 {} 天数据完整(每日应用数≥{})，无需补采", backfillDays, minApps);
            return;
        }
        log.info("服务负载启动补采: 缺 {} 天(完整日应用数={}): {}", missing.size(), maxApps, missing);

        int consecutiveEmpty = 0;
        for (LocalDate date : missing) {
            try {
                int collected = serviceLoadService.collectFromSls(date);
                if (collected > 0) {
                    consecutiveEmpty = 0;
                    log.info("补采完成: date={}, 写入 {} 条", date, collected);
                } else {
                    consecutiveEmpty++;
                    log.warn("补采无数据写入: date={}, 连续空采 {}/2", date, consecutiveEmpty);
                }
            } catch (Exception e) {
                consecutiveEmpty++;
                log.warn("补采失败: date={}, err={}, 连续空采 {}/2", date, e.getMessage(), consecutiveEmpty);
            }
            if (consecutiveEmpty >= 2) {
                log.warn("连续 {} 天采集为空，疑似数据源不可达，终止启动补采", consecutiveEmpty);
                return;
            }
        }
        log.info("服务负载启动补采结束");
    }
}
