package com.ykc.hubble.service;

/**
 * 健康度评估：基于告警阈值把日志命中量翻译成红/粉两档染色，正常不染色。
 * <p>
 * 大盘只标“告警（红）”和“预警（粉）”，正常的无需特意标色。
 * 规则与前端 health.js 保持一致。
 *
 * @author Cloud Eyes Team
 */
public final class HealthEvaluator {

    /**
     * 健康等级
     */
    public enum Status {
        /** 正常（不染色） */
        NORMAL,
        /** 预警（达到阈值的一半） */
        YELLOW,
        /** 告警（达到或超过阈值） */
        RED
    }

    private HealthEvaluator() {
    }

    /**
     * 根据当前命中量与阈值评估健康度（默认粉盘比例 0.5）
     */
    public static Status evaluate(long count, Integer threshold) {
        return evaluate(count, threshold, 0.5);
    }

    /**
     * 根据当前命中量与阈值评估健康度
     *
     * @param count                当前日志命中量
     * @param threshold            告警阈值；为空或非正时无法判定，视为正常
     * @param yellowThresholdRatio 粉盘阈值比例（0~1），为空时使用默认 0.5
     */
    public static Status evaluate(long count, Integer threshold, Double yellowThresholdRatio) {
        if (threshold == null || threshold <= 0) {
            return Status.NORMAL;
        }
        double ratio = (yellowThresholdRatio != null && yellowThresholdRatio > 0 && yellowThresholdRatio < 1)
                ? yellowThresholdRatio : 0.5;
        if (count >= threshold) {
            return Status.RED;
        }
        if (count >= threshold * ratio) {
            return Status.YELLOW;
        }
        return Status.NORMAL;
    }
}
