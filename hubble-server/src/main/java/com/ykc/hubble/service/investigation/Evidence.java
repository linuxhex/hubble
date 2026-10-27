package com.ykc.hubble.service.investigation;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单路取证结果。degraded=true 表示该路证据获取失败/超时/为空，note 说明原因，
 * 编排层照常把降级信息带给 LLM，不中断调查。
 */
@Data
@AllArgsConstructor
public class Evidence {

    private String name;
    private String content;
    private boolean degraded;
    private String note;

    public static Evidence ok(String name, String content) {
        return new Evidence(name, content, false, null);
    }

    public static Evidence degraded(String name, String note) {
        return new Evidence(name, "", true, note);
    }
}
