package com.ykc.hubble.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ykc.hubble.common.Result;
import com.ykc.hubble.entity.IncidentMemory;
import com.ykc.hubble.service.AlertInvestigationService;
import com.ykc.hubble.service.IncidentMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * AI 事故调查记录：列表 / 详情 / 失败重试
 */
@Slf4j
@RestController
@RequestMapping("/api/incident-investigations")
@RequiredArgsConstructor
@Tag(name = "Incident Investigation", description = "AI 事故调查记录")
public class IncidentInvestigationController {

    private final IncidentMemoryService incidentMemoryService;
    private final AlertInvestigationService alertInvestigationService;

    @GetMapping
    @Operation(summary = "分页查询调查记录", description = "支持按告警来源（ARMS/DEP_RT/DEP_ERR/MW）和状态（INVESTIGATING/DONE/FAILED）过滤")
    public Result<Page<IncidentMemory>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status) {
        return Result.success(incidentMemoryService.page(page, Math.min(size, 100), source, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "调查记录详情", description = "含完整根因报告与取证证据")
    public Result<IncidentMemory> detail(@PathVariable Long id) {
        IncidentMemory record = incidentMemoryService.getById(id);
        if (record == null) {
            return Result.error("记录不存在: " + id);
        }
        return Result.success(record);
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "重试失败调查", description = "仅 FAILED 状态可重试")
    public Result<String> retry(@PathVariable Long id) {
        boolean ok = alertInvestigationService.retry(id);
        return ok ? Result.success("已重新提交调查") : Result.error("重试失败（仅 FAILED 状态可重试）");
    }
}
