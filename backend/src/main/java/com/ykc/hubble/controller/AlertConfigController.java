package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.entity.AlertConfig;
import com.ykc.hubble.service.AlertConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 日志监控配置控制器（对齐前端 src/api/alert.js：query 查询组 + mgmt 管理组）
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/alert-config")
@RequiredArgsConstructor
@Tag(name = "日志监控配置服务", description = "监控项的增删改查与启停")
public class AlertConfigController {

    private final AlertConfigService alertConfigService;

    @GetMapping("/query")
    @Operation(summary = "分页查询监控配置", description = "支持标题模糊与启用状态过滤")
    public Result<Map<String, Object>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(alertConfigService.page(current, size, title, enabled));
    }

    @GetMapping("/query/{id}")
    @Operation(summary = "监控配置详情")
    public Result<AlertConfig> detail(@PathVariable Long id) {
        return Result.success(alertConfigService.detail(id));
    }

    @PostMapping("/mgmt")
    @Operation(summary = "创建监控配置")
    public Result<Map<String, Long>> create(@RequestBody AlertConfig cfg) {
        Long id = alertConfigService.create(cfg);
        return Result.success(Map.of("id", id));
    }

    @PutMapping("/mgmt/{id}")
    @Operation(summary = "更新监控配置")
    public Result<Void> update(@PathVariable Long id, @RequestBody AlertConfig cfg) {
        alertConfigService.update(id, cfg);
        return Result.success();
    }

    @DeleteMapping("/mgmt/{id}")
    @Operation(summary = "删除监控配置")
    public Result<Void> delete(@PathVariable Long id) {
        alertConfigService.delete(id);
        return Result.success();
    }

    @PutMapping("/mgmt/{id}/enable")
    @Operation(summary = "启用监控配置")
    public Result<Void> enable(@PathVariable Long id) {
        alertConfigService.setEnabled(id, true);
        return Result.success();
    }

    @PutMapping("/mgmt/{id}/disable")
    @Operation(summary = "禁用监控配置")
    public Result<Void> disable(@PathVariable Long id) {
        alertConfigService.setEnabled(id, false);
        return Result.success();
    }
}
