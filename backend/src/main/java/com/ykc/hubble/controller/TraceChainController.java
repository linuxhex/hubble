package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.service.TraceChainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/gateway/trace")
@RequiredArgsConstructor
public class TraceChainController {

    private final TraceChainService traceChainService;

    @GetMapping("/{traceId}")
    public Result<Map<String, Object>> queryTraceChain(
            @PathVariable String traceId,
            @RequestParam(defaultValue = "1h") String timeRange) {
        return Result.success(traceChainService.queryTraceChain(traceId, timeRange));
    }
}
