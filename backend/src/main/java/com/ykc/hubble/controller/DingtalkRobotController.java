package com.ykc.hubble.controller;

import com.ykc.hubble.common.Result;
import com.ykc.hubble.entity.DingtalkRobot;
import com.ykc.hubble.service.DingtalkRobotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 钉钉机器人管理：CRUD + 测试发送 + 查告警规则绑定。
 */
@Slf4j
@RestController
@RequestMapping("/dingtalk-robot")
@RequiredArgsConstructor
@Tag(name = "钉钉机器人管理", description = "多机器人 CRUD 与测试发送")
public class DingtalkRobotController {

    private final DingtalkRobotService dingtalkRobotService;

    @GetMapping("/list")
    @Operation(summary = "列出所有机器人")
    public Result<List<DingtalkRobot>> list() {
        return Result.success(dingtalkRobotService.list());
    }

    @GetMapping("/list-enabled")
    @Operation(summary = "列出启用的机器人（供告警规则多选）")
    public Result<List<DingtalkRobot>> listEnabled() {
        return Result.success(dingtalkRobotService.listEnabled());
    }

    @GetMapping("/{id}")
    @Operation(summary = "机器人详情")
    public Result<DingtalkRobot> detail(@PathVariable Long id) {
        return Result.success(dingtalkRobotService.detail(id));
    }

    @PostMapping("")
    @Operation(summary = "新增机器人")
    public Result<Map<String, Long>> create(@RequestBody DingtalkRobot robot) {
        Long id = dingtalkRobotService.create(robot);
        return Result.success(Map.of("id", id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新机器人")
    public Result<Void> update(@PathVariable Long id, @RequestBody DingtalkRobot robot) {
        dingtalkRobotService.update(id, robot);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除机器人")
    public Result<Void> delete(@PathVariable Long id) {
        dingtalkRobotService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用机器人")
    public Result<Void> enable(@PathVariable Long id) {
        dingtalkRobotService.setEnabled(id, true);
        return Result.success();
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "禁用机器人")
    public Result<Void> disable(@PathVariable Long id) {
        dingtalkRobotService.setEnabled(id, false);
        return Result.success();
    }

    @PostMapping("/test/{id}")
    @Operation(summary = "测试发送消息到该机器人对应的群")
    public Result<Void> testSend(@PathVariable Long id) {
        dingtalkRobotService.testSend(id);
        return Result.success();
    }

    @GetMapping("/by-alert-config/{configId}")
    @Operation(summary = "查告警规则绑定的机器人")
    public Result<List<DingtalkRobot>> byAlertConfig(@PathVariable Long configId) {
        return Result.success(dingtalkRobotService.listByAlertConfigId(configId));
    }
}
