package com.ykc.hubble.controller;

import com.ykc.hubble.entity.ServiceLoadDaily;
import com.ykc.hubble.service.ServiceLoadAssessmentService;
import com.ykc.hubble.service.ServiceLoadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/service-load")
@RequiredArgsConstructor
public class ServiceLoadController {

    private final ServiceLoadService serviceLoadService;
    private final ServiceLoadAssessmentService assessmentService;

    /**
     * 扩容决策评估：全服务峰值水位 + 环比 + 触顶预测 + 建议分级
     */
    @GetMapping("/assessment")
    public Map<String, Object> getAssessment(@RequestParam(defaultValue = "30") int days) {
        if (days < 7) days = 7;
        if (days > 365) days = 365;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("data", assessmentService.assessAll(days));
        return result;
    }

    /**
     * 获取应用列表
     */
    @GetMapping("/apps")
    public Map<String, Object> listApps() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("data", serviceLoadService.listAppNames());
        return result;
    }

    /**
     * 查询指定应用的负载数据（最近一年）
     */
    @GetMapping("/trend")
    public Map<String, Object> getTrend(
            @RequestParam String appName,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate) {

        if (startDate == null) {
            startDate = LocalDate.now().minusMonths(12);
        }

        List<ServiceLoadDaily> data = serviceLoadService.queryByAppNameAndDateRange(appName, startDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    /**
     * 查询所有应用的负载数据（列表展示用）
     */
    @GetMapping("/list")
    public Map<String, Object> getList(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate) {

        if (startDate == null) {
            startDate = LocalDate.now().minusMonths(12);
        }

        List<ServiceLoadDaily> data = serviceLoadService.queryAllByDateRange(startDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    /**
     * 手动触发采集指定日期的数据
     */
    @PostMapping("/collect")
    public Map<String, Object> manualCollect(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            serviceLoadService.manualCollect(date);
            result.put("code", 200);
            result.put("message", "采集成功");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "采集失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 生成演示数据（用于测试页面功能）
     */
    @PostMapping("/generate-demo-data")
    public Map<String, Object> generateDemoData() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            serviceLoadService.generateDemoData();
            result.put("code", 200);
            result.put("message", "演示数据生成成功");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "生成失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 调试：发现 SLS 中实际的容器名称
     */
    @GetMapping("/discover-services")
    public Map<String, Object> discoverServices() {
        return serviceLoadService.discoverServicesFromSls();
    }
}
