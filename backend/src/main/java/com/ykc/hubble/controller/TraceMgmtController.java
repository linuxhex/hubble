package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.dto.BusinessTraceDTO;
import com.ykc.hubble.service.DictService;
import com.ykc.hubble.service.TraceMgmtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 业务链路控制器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/traces/mgmt")
@RequiredArgsConstructor
@Tag(name = "业务链路管理服务", description = "业务链路配置的增删改查")
public class TraceMgmtController {

    private final TraceMgmtService traceService;
    private final DictService dictService;

    @PostMapping
    @Operation(summary = "创建业务链路", description = "创建新的业务链路配置")
    public Result<Map<String, Long>> createTrace(@Validated @RequestBody BusinessTraceDTO dto) {
        Long id = traceService.createTrace(dto);
        Map<String, Long> data = new HashMap<>();
        data.put("id", id);
        return Result.success(data);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新业务链路", description = "更新业务链路配置")
    public Result<Void> updateTrace(
            @Parameter(description = "业务链路ID", required = true)
            @PathVariable Long id,
            @Validated @RequestBody BusinessTraceDTO dto
    ) {
        traceService.updateTrace(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除业务链路", description = "删除业务链路配置（级联删除节点）")
    public Result<Void> deleteTrace(
            @Parameter(description = "业务链路ID", required = true)
            @PathVariable Long id
    ) {
        traceService.deleteTrace(id);
        return Result.success();
    }

    // ========== 业务分类管理（使用字典） ==========

    @PostMapping("/categories/add")
    @Operation(summary = "创建业务分类", description = "创建新的业务分类（字典值）")
    public Result<Map<String, String>> createCategory(
            @Parameter(description = "分类名称（字典值）", required = true)
            @RequestParam String value
    ) {
        dictService.addDictValue(DictService.DICT_TYPE_CATEGORY, value, null);
        Map<String, String> data = new HashMap<>();
        data.put("value", value);
        return Result.success(data);
    }

    @DeleteMapping("/categories/delete")
    @Operation(summary = "删除业务分类", description = "删除业务分类（字典值）")
    public Result<Void> deleteCategory(
            @Parameter(description = "分类名称（字典值）", required = true)
            @RequestParam String value
    ) {
        dictService.deleteDictValue(DictService.DICT_TYPE_CATEGORY, value);
        return Result.success();
    }
}
