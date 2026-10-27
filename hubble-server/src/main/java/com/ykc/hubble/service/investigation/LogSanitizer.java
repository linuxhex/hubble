package com.ykc.hubble.service.investigation;

/**
 * 日志/链路送 LLM 前的脱敏工具：掩码 IP、pod 名、集群/账号 ID 等基础设施敏感信息。
 * 业务语义（服务名、接口路径、错误文案）保留，不影响根因分析。
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** IPv4 → 首段保留，其余掩码 */
    private static final String IPV4 = "(?<!\\d)(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)";
    /** pod 名（deployment-<hash> 形态）→ <pod> */
    private static final String POD_NAME = "(?i)\\b([a-z][a-z0-9-]{2,40})-[a-f0-9]{5,10}\\b";
    /** 长数字串（账号/实例/集群 ID，≥8 位）→ *** */
    private static final String LONG_NUMBER = "(?<!\\d)\\d{8,}(?!\\d)";
    /** 长十六进制串（≥16 位，容器/集群 hash）→ *** */
    private static final String LONG_HEX = "(?i)(?<![a-f0-9])[a-f0-9]{16,}(?![a-f0-9])";
    /** 手机号 → 掩码保留前 3 后 4 */
    private static final String PHONE = "(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)";

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text
                .replaceAll(IPV4, "$1.***.***.***")
                .replaceAll(POD_NAME, "<pod>")
                .replaceAll(LONG_HEX, "***")
                .replaceAll(LONG_NUMBER, "***")
                .replaceAll(PHONE, "$1****$2");
    }
}
