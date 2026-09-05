package com.ykc.hubble.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ykc.hubble.client.DingTalkClient;
import com.ykc.hubble.entity.AlertConfigRobot;
import com.ykc.hubble.entity.DingtalkRobot;
import com.ykc.hubble.mapper.AlertConfigRobotMapper;
import com.ykc.hubble.mapper.DingtalkRobotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 钉钉机器人管理：CRUD + 测试发送 + 查告警规则绑定的机器人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingtalkRobotService {

    private final DingtalkRobotMapper robotMapper;
    private final AlertConfigRobotMapper configRobotMapper;
    private final DingTalkClient dingTalkClient;

    /** 列出所有机器人 */
    public List<DingtalkRobot> list() {
        return robotMapper.selectList(new LambdaQueryWrapper<DingtalkRobot>().orderByDesc(DingtalkRobot::getId));
    }

    /** 列出启用的机器人（供告警规则多选） */
    public List<DingtalkRobot> listEnabled() {
        return robotMapper.selectList(new LambdaQueryWrapper<DingtalkRobot>()
                .eq(DingtalkRobot::getEnabled, 1)
                .orderByDesc(DingtalkRobot::getId));
    }

    public DingtalkRobot detail(Long id) {
        return robotMapper.selectById(id);
    }

    public Long create(DingtalkRobot robot) {
        robot.setEnabled(robot.getEnabled() == null ? 1 : robot.getEnabled());
        robotMapper.insert(robot);
        return robot.getId();
    }

    public void update(Long id, DingtalkRobot robot) {
        robot.setId(id);
        robotMapper.updateById(robot);
    }

    public void delete(Long id) {
        robotMapper.deleteById(id);
        // 同步删除绑定关系
        configRobotMapper.delete(new LambdaQueryWrapper<AlertConfigRobot>()
                .eq(AlertConfigRobot::getRobotId, id));
    }

    public void setEnabled(Long id, boolean enabled) {
        DingtalkRobot robot = new DingtalkRobot();
        robot.setId(id);
        robot.setEnabled(enabled ? 1 : 0);
        robotMapper.updateById(robot);
    }

    /** 测试发送：发一条测试消息到该机器人对应的钉钉群 */
    public boolean testSend(Long robotId) {
        DingtalkRobot robot = robotMapper.selectById(robotId);
        if (robot == null || !StringUtils.hasText(robot.getWebhook())) {
            throw new IllegalArgumentException("机器人不存在或 Webhook 未配置");
        }
        String title = "Hubble 告警测试";
        String markdown = "### ✅ 钉钉机器人测试通知\n\n" +
                "> 这是一条来自 Hubble 监控大盘的测试消息\n\n" +
                "> 机器人：**" + robot.getName() + "**\n\n" +
                "> 如果你在群里看到这条消息，说明机器人配置正确 ✓\n\n" +
                "> 测试时间：" + java.time.LocalDateTime.now() + "\n\n";
        try {
            dingTalkClient.sendRobotActionCard(robot.getWebhook(), robot.getSecret(),
                    title, markdown, "查看大盘", "http://localhost:5173", true);
            return true;
        } catch (Exception e) {
            log.error("测试发送钉钉消息失败: robotId={}, error={}", robotId, e.getMessage());
            throw new RuntimeException("测试发送失败: " + e.getMessage());
        }
    }

    /** 查告警规则绑定的机器人列表 */
    public List<DingtalkRobot> listByAlertConfigId(Long configId) {
        List<Long> robotIds = configRobotMapper.selectRobotIdsByConfigId(configId);
        if (robotIds.isEmpty()) {
            return Collections.emptyList();
        }
        return robotMapper.selectBatchIds(robotIds);
    }

    /** 查告警规则绑定的机器人 ID 列表 */
    public List<Long> listRobotIdsByConfigId(Long configId) {
        return configRobotMapper.selectRobotIdsByConfigId(configId);
    }

    /** 保存告警规则与机器人的绑定关系（先删后插） */
    public void saveBindings(Long configId, List<Long> robotIds) {
        configRobotMapper.deleteByConfigId(configId);
        if (robotIds == null || robotIds.isEmpty()) {
            return;
        }
        List<AlertConfigRobot> bindings = new ArrayList<>();
        for (Long robotId : robotIds) {
            bindings.add(new AlertConfigRobot(configId, robotId));
        }
        for (AlertConfigRobot binding : bindings) {
            configRobotMapper.insert(binding);
        }
    }
}
