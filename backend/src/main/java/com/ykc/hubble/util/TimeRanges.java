package com.ykc.hubble.util;

/**
 * 时间范围字符串解析工具：把前端的 "15m"/"30m"/"6h"/"1d" 转成秒
 *
 * @author Cloud Eyes Team
 */
public final class TimeRanges {

    /** 默认时间范围（15 分钟），单位秒 */
    public static final long DEFAULT_SECONDS = 15 * 60L;

    private TimeRanges() {
    }

    /**
     * 将 "15m"/"30m"/"6h"/"1d" 等解析为秒；纯数字视为秒；无法解析回退到 15 分钟
     */
    public static long toSeconds(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            return DEFAULT_SECONDS;
        }
        String s = timeRange.trim().toLowerCase();
        try {
            if (s.endsWith("m")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 60L;
            }
            if (s.endsWith("h")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 3600L;
            }
            if (s.endsWith("d")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 86400L;
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return DEFAULT_SECONDS;
        }
    }
}
