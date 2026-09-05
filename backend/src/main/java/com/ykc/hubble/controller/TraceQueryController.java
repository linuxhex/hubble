package com.ykc.hubble.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ykc.hubble.common.Result;
import com.ykc.hubble.dto.QueryRequestDTO;
import com.ykc.hubble.dto.UserBehaviorTraceQueryDTO;
import com.ykc.hubble.entity.BusinessTrace;
import com.ykc.hubble.entity.NodeQueryResult;
import com.ykc.hubble.entity.TraceNode;
import com.ykc.hubble.mapper.TraceNodeMapper;
import com.ykc.hubble.service.DictService;
import com.ykc.hubble.service.TraceMgmtService;
import com.ykc.hubble.service.TraceQueryService;
import com.ykc.hubble.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 查询控制器
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@RestController
@RequestMapping("/api/traces/query")
@RequiredArgsConstructor
@Tag(name = "业务链路查询服务", description = "业务链路查询服务")
public class TraceQueryController {

    private final TraceQueryService queryService;
    private final TraceNodeMapper traceNodeMapper;
    private final TraceMgmtService traceService;
    private final DictService dictService;

    @GetMapping("/list")
    @Operation(summary = "获取业务链路列表", description = "分页查询业务链路列表，支持关键字搜索和分类筛选")
    public Result<Map<String, Object>> getTraceList(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "搜索关键字")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "业务分类（字典值）")
            @RequestParam(required = false) String category
    ) {
        Page<BusinessTrace> pageResult = traceService.getTraceList(page, pageSize, keyword, category);

        List<BusinessTraceSummaryVO> list = pageResult.getRecords().stream()
                .map(trace -> {
                    BusinessTraceSummaryVO vo = new BusinessTraceSummaryVO();
                    BeanUtils.copyProperties(trace, vo);
                    // 查询节点数量
                    LambdaQueryWrapper<TraceNode> nodeWrapper = new LambdaQueryWrapper<>();
                    nodeWrapper.eq(TraceNode::getTraceId, trace.getId());
                    Long nodeCount = traceNodeMapper.selectCount(nodeWrapper);
                    vo.setNodeCount(nodeCount.intValue());
                    // 查询分类名称
                    if (trace.getCategory() != null && !trace.getCategory().trim().isEmpty()) {
                        String categoryName = dictService.getDictLabel(DictService.DICT_TYPE_CATEGORY, trace.getCategory());
                        if (categoryName != null) {
                            vo.setCategoryName(categoryName);
                        }
                    }
                    return vo;
                })
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("total", pageResult.getTotal());
        data.put("list", list);

        return Result.success(data);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取业务链路详情", description = "获取业务链路详情，包含节点列表")
    public Result<BusinessTraceDetailVO> getTraceDetail(
            @Parameter(description = "业务链路ID", required = true)
            @PathVariable Long id,
            @Parameter(description = "是否包含所有节点（包括子节点），用于编辑", required = false)
            @RequestParam(required = false, defaultValue = "false") Boolean includeChildren
    ) {
        BusinessTrace trace;
        if (Boolean.TRUE.equals(includeChildren)) {
            // 编辑模式：返回所有节点（包括子节点）
            trace = traceService.getTraceDetailWithAllNodes(id);
        } else {
            // 查询模式：只返回顶级节点
            trace = traceService.getTraceDetail(id);
        }

        BusinessTraceDetailVO vo = new BusinessTraceDetailVO();
        BeanUtils.copyProperties(trace, vo);

        // 查询分类名称
        if (trace.getCategory() != null && !trace.getCategory().trim().isEmpty()) {
            String categoryName = dictService.getDictLabel(DictService.DICT_TYPE_CATEGORY, trace.getCategory());
            if (categoryName != null) {
                vo.setCategoryName(categoryName);
            }
        }

        List<TraceNodeVO> nodeVOs = trace.getNodes().stream()
                .map(node -> {
                    TraceNodeVO nodeVO = new TraceNodeVO();
                    BeanUtils.copyProperties(node, nodeVO);
                    // 只在查询模式下检查是否有子节点（编辑模式不需要）
                    if (!Boolean.TRUE.equals(includeChildren) && node.getParentId() == null) {
                        nodeVO.setHasChildren(traceService.hasChildren(node.getId()));
                    }
                    return nodeVO;
                })
                .collect(Collectors.toList());
        vo.setNodes(nodeVOs);

        return Result.success(vo);
    }

    @GetMapping("/nodes/{nodeId}/children")
    @Operation(summary = "获取节点的子节点列表", description = "获取指定节点的子节点列表")
    public Result<List<TraceNodeVO>> getChildNodes(
            @Parameter(description = "父节点ID", required = true)
            @PathVariable Long nodeId
    ) {
        List<TraceNode> childNodes = traceService.getChildNodes(nodeId);
        List<TraceNodeVO> nodeVOs = childNodes.stream()
                .map(node -> {
                    TraceNodeVO nodeVO = new TraceNodeVO();
                    BeanUtils.copyProperties(node, nodeVO);
                    // 子节点不应该再有子节点（只支持一级子节点）
                    nodeVO.setHasChildren(false);
                    return nodeVO;
                })
                .collect(Collectors.toList());
        return Result.success(nodeVOs);
    }

    @GetMapping("/{id}/variables")
    @Operation(summary = "获取查询变量列表", description = "从节点的查询模板中提取所有变量")
    public Result<List<String>> getTraceVariables(
            @Parameter(description = "业务链路ID", required = true)
            @PathVariable Long id
    ) {
        List<String> variables = traceService.extractVariables(id);
        return Result.success(variables);
    }

    @GetMapping("/categories")
    @Operation(summary = "获取所有业务分类", description = "获取所有业务分类列表（从字典获取）")
    public Result<List<String>> getAllCategories() {
        List<String> categories = dictService.getDictValues(DictService.DICT_TYPE_CATEGORY);
        return Result.success(categories);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "执行日志查询（SSE流式返回）", description = "执行业务链路的顶级节点日志查询，实时推送各节点的查询结果")
    public SseEmitter queryWithSSE(@Validated @RequestBody QueryRequestDTO request) {
        SseEmitter emitter = new SseEmitter(30000L); // 30秒超时

        // 异步执行查询
        CompletableFuture.runAsync(() -> {
            try {
                // 执行查询
                Map<String, NodeQueryResult> results = queryService.executeQuery(
                        request.getTraceId(),
                        request.getVariables(),
                        request.getTimeRange().getFrom(),
                        request.getTimeRange().getTo()
                );

                // 推送每个节点的结果
                for (NodeQueryResult result : results.values()) {
                    NodeQueryResultVO vo = convertToVO(result);
                    emitter.send(SseEmitter.event()
                            .name("data")
                            .data(vo));
                }

                // 发送完成事件
                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(Map.of("message", "查询完成")));
                emitter.complete();

            } catch (Exception e) {
                log.error("查询失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", "查询失败: " + e.getMessage())));
                    emitter.completeWithError(e);
                } catch (IOException ioException) {
                    log.error("发送错误事件失败", ioException);
                }
            }
        });

        return emitter;
    }

    @PostMapping(value = "/nodes/{nodeId}/children/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "查询子节点日志（SSE流式返回）", description = "查询指定节点的子节点日志，实时推送各子节点的查询结果")
    public SseEmitter queryChildNodesWithSSE(
            @PathVariable Long nodeId,
            @Validated @RequestBody QueryRequestDTO request) {
        SseEmitter emitter = new SseEmitter(30000L); // 30秒超时

        // 异步执行查询
        CompletableFuture.runAsync(() -> {
            try {
                // 执行子节点查询
                Map<String, NodeQueryResult> results = queryService.executeChildNodesQuery(
                        nodeId,
                        request.getVariables(),
                        request.getTimeRange().getFrom(),
                        request.getTimeRange().getTo()
                );

                // 推送每个子节点的结果
                for (NodeQueryResult result : results.values()) {
                    NodeQueryResultVO vo = convertToVO(result);
                    emitter.send(SseEmitter.event()
                            .name("data")
                            .data(vo));
                }

                // 发送完成事件
                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(Map.of("message", "查询完成")));
                emitter.complete();

            } catch (Exception e) {
                log.error("查询子节点失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", "查询失败: " + e.getMessage())));
                    emitter.completeWithError(e);
                } catch (IOException ioException) {
                    log.error("发送错误事件失败", ioException);
                }
            }
        });

        return emitter;
    }

    @PostMapping("/export")
    @Operation(summary = "导出查询结果", description = "将查询结果导出为CSV格式")
    public ResponseEntity<byte[]> exportResults(@Validated @RequestBody QueryRequestDTO request) {
        try {
            // 执行查询
            Map<String, NodeQueryResult> results = queryService.executeQuery(
                    request.getTraceId(),
                    request.getVariables(),
                    request.getTimeRange().getFrom(),
                    request.getTimeRange().getTo()
            );

            // 生成CSV内容
            StringBuilder csv = new StringBuilder();
            csv.append("节点名称,状态,日志数量,时间,级别,容器IP,追踪ID,消息\n");

            for (NodeQueryResult result : results.values()) {
                String nodeName = result.getNodeName();
                String status = result.getStatus();
                int logCount = result.getLogCount();

                if (result.getLogs() != null && !result.getLogs().isEmpty()) {
                    for (var log : result.getLogs()) {
                        csv.append(String.format("%s,%s,%d,%s,%s,%s,%s,\"%s\"\n",
                                escapeCsv(nodeName),
                                status,
                                logCount,
                                escapeCsv(log.getTime()),
                                escapeCsv(log.getLevel()),
                                escapeCsv(log.getContainerIp()),
                                escapeCsv(log.getTrace()),
                                escapeCsv(log.getMessage())
                        ));
                    }
                } else {
                    // 即使没有日志，也记录节点信息
                    csv.append(String.format("%s,%s,%d,,,,\n",
                            escapeCsv(nodeName),
                            status,
                            logCount
                    ));
                }
            }

            // 设置响应头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
            headers.setContentDispositionFormData("attachment", "query_results.csv");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(csv.toString().getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            log.error("导出失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 转义CSV字段
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // 转义引号和换行符
        return value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ");
    }

    /**
     * 转换为VO
     */
    private NodeQueryResultVO convertToVO(NodeQueryResult result) {
        NodeQueryResultVO vo = new NodeQueryResultVO();
        BeanUtils.copyProperties(result, vo);

        if (result.getLogs() != null) {
            List<LogEntryVO> logVOs = result.getLogs().stream()
                    .map(log -> {
                        LogEntryVO logVO = new LogEntryVO();
                        BeanUtils.copyProperties(log, logVO);
                        return logVO;
                    })
                    .collect(Collectors.toList());
            vo.setLogs(logVOs);
        }

        return vo;
    }

    @PostMapping("/user-behavior")
    @Operation(summary = "查询用户行为轨迹", description = "根据关键字查询用户在应用中的操作行为轨迹")
    public Result<UserBehaviorTraceVO> queryUserBehaviorTrace(
            @Validated @RequestBody UserBehaviorTraceQueryDTO request) {
        log.info("查询用户行为轨迹: keyword={}, date={}, limit={}", 
                request.getKeyword(), request.getDate(), request.getLimit());
        
        UserBehaviorTraceVO result = queryService.queryUserBehaviorTrace(
                request.getKeyword(),
                request.getDate(),
                request.getTimeRange(),
                request.getOffset(),
                request.getLimit()
        );
        
        return Result.success(result);
    }
}
