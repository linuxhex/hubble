package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.TraceChainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gateway/trace")
@RequiredArgsConstructor
public class TraceChainController {

    private final TraceChainService traceChainService;

    @GetMapping("/search")
    public Result<List<Map<String, Object>>> searchTracesByApi(
            @RequestParam String apiPath,
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.success(traceChainService.searchTracesByApi(apiPath, timeRange, limit));
    }

    @GetMapping("/{traceId}")
    public Result<Map<String, Object>> queryTraceChain(
            @PathVariable String traceId,
            @RequestParam(defaultValue = "1h") String timeRange,
            @RequestParam(required = false) String timestamp) {
        return Result.success(traceChainService.queryTraceChain(traceId, timeRange, timestamp));
    }
}
