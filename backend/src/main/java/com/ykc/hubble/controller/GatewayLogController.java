package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.dto.GatewayLogQueryDTO;
import com.ykc.hubble.service.GatewayLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/gateway/logs")
@RequiredArgsConstructor
public class GatewayLogController {

    private final GatewayLogService gatewayLogService;

    @PostMapping("/query")
    public Result<Map<String, Object>> queryLogs(@RequestBody GatewayLogQueryDTO dto) {
        return Result.success(gatewayLogService.queryLogs(dto));
    }
}
