package com.ykc.hubble.controller;

import com.ykc.hubble.dto.ArmsWebhookPayload;
import com.ykc.hubble.service.ArmsWebhookService;
import com.ykc.hubble.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * ARMS 告警 Webhook 接收端点
 * 阿里云 ARMS 通过此端点推送告警通知（AlertManager 格式）
 */
@Slf4j
@RestController
@RequestMapping("/api/arms-webhook")
@RequiredArgsConstructor
@Tag(name = "ARMS Webhook", description = "接收阿里云 ARMS 告警回调")
public class ArmsWebhookController {

    private final ArmsWebhookService armsWebhookService;

    @PostMapping("/receive")
    @Operation(summary = "接收 ARMS 告警回调", description = "接收阿里云 ARMS 通过 Webhook 推送的告警通知（AlertManager 格式）")
    public Result<String> receive(@RequestBody ArmsWebhookPayload payload) {
        try {
            armsWebhookService.handleWebhook(payload);
            return Result.success("ok");
        } catch (Exception e) {
            log.error("ARMS Webhook 处理失败: {}", e.getMessage(), e);
            return Result.error("处理失败: " + e.getMessage());
        }
    }
}
