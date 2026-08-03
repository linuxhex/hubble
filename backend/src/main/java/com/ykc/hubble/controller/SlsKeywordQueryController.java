package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.SlsKeywordService;
import com.ykc.hubble.vo.SlsKeywordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SLS关键字模版控制器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/sls-keywords/query")
@RequiredArgsConstructor
@Tag(name = "SLS关键字模版管理", description = "SLS关键字模版的增删改查")
public class SlsKeywordQueryController {

    private final SlsKeywordService slsKeywordService;

    @GetMapping
    @Operation(summary = "获取SLS关键字模版列表", description = "分页查询SLS关键字模版列表，支持关键字搜索、应用筛选和标签模糊查询")
    public Result<Map<String, Object>> getSlsKeywordList(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "搜索关键字")
            @RequestParam(required = false) String desc,
            @Parameter(description = "应用名称")
            @RequestParam(required = false) String application,
            @Parameter(description = "标签（模糊查询）")
            @RequestParam(required = false) String tag
    ) {
        Map<String, Object> result = slsKeywordService.getSlsKeywordList(page, pageSize, desc, application, tag);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取SLS关键字模版详情", description = "获取SLS关键字模版详情")
    public Result<SlsKeywordVO> getSlsKeywordDetail(
            @Parameter(description = "模版ID", required = true)
            @PathVariable String id
    ) {
        SlsKeywordVO vo = slsKeywordService.getSlsKeywordDetail(id);
        return Result.success(vo);
    }

    @GetMapping("/applications")
    @Operation(summary = "获取应用列表", description = "获取所有可用的应用列表，用于下拉框选择")
    public Result<List<String>> getApplicationList() {
        List<String> applications = slsKeywordService.getApplicationList();
        return Result.success(applications);
    }

    @GetMapping("/tags")
    @Operation(summary = "获取标签列表", description = "获取所有可用的标签列表，用于多选下拉框")
    public Result<List<String>> getTagList() {
        List<String> tags = slsKeywordService.getTagList();
        return Result.success(tags);
    }
}

