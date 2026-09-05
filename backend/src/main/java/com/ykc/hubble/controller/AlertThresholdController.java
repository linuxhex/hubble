package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.entity.AlertThresholdConfig;
import com.ykc.hubble.service.AlertThresholdService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警阈值配置：接口劣化/流量暴涨/红黄盘等阈值统一管理。
 */
@RestController
@RequestMapping("/alert-threshold")
@RequiredArgsConstructor
@Tag(name = "告警阈值配置", description = "服务告警阈值统一配置")
public class AlertThresholdController {

    private final AlertThresholdService alertThresholdService;

    @GetMapping("/list")
    @Operation(summary = "列出所有阈值配置")
    public Result<List<AlertThresholdConfig>> list() {
        return Result.success(alertThresholdService.list());
    }

    @PutMapping("/{key}")
    @Operation(summary = "更新阈值")
    public Result<Void> update(@PathVariable String key, @RequestBody Map<String, String> body) {
        alertThresholdService.update(key, body.get("value"));
        return Result.success();
    }
}
