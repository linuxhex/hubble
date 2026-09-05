package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.dto.DictValueDTO;
import com.ykc.hubble.dto.SlsKeywordDTO;
import com.ykc.hubble.service.SlsKeywordService;
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
 * SLS关键字模版控制器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/api/sls-keywords/mgmt")
@RequiredArgsConstructor
@Tag(name = "SLS关键字模版管理", description = "SLS关键字模版的增删改查")
public class SlsKeywordMgmtController {

    private final SlsKeywordService slsKeywordService;

    @PostMapping
    @Operation(summary = "创建SLS关键字模版", description = "创建新的SLS关键字模版")
    public Result<Map<String, String>> createSlsKeyword(@Validated @RequestBody SlsKeywordDTO dto) {
        String id = slsKeywordService.createSlsKeyword(dto);
        Map<String, String> data = new HashMap<>();
        data.put("id", id);
        return Result.success(data);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新SLS关键字模版", description = "更新SLS关键字模版配置")
    public Result<Void> updateSlsKeyword(
            @Parameter(description = "模版ID", required = true)
            @PathVariable String id,
            @Validated @RequestBody SlsKeywordDTO dto
    ) {
        slsKeywordService.updateSlsKeyword(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除SLS关键字模版", description = "删除SLS关键字模版")
    public Result<Void> deleteSlsKeyword(
            @Parameter(description = "模版ID", required = true)
            @PathVariable String id
    ) {
        slsKeywordService.deleteSlsKeyword(id);
        return Result.success();
    }

    @PostMapping("/applications/add")
    @Operation(summary = "新增应用", description = "新增应用名称到字典，展示值自动使用应用名称")
    public Result<Void> addApplication(@Validated @RequestBody DictValueDTO dto) {
        slsKeywordService.addApplication(dto.getValue());
        return Result.success();
    }

    @PostMapping("/tags/add")
    @Operation(summary = "新增标签", description = "新增标签名称到字典，展示值自动使用标签名称")
    public Result<Void> addTag(@Validated @RequestBody DictValueDTO dto) {
        slsKeywordService.addTag(dto.getValue());
        return Result.success();
    }
}

