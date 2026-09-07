package com.ykc.hubble.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一次采集得到的时序点
 *
 * @author Cloud Eyes Team
 */
@Data
@AllArgsConstructor
public class SnapshotPoint {

    /**
     * 采集时间（Unix 时间戳，秒）
     */
    private long collectedAt;

    /**
     * 命中日志条数
     */
    private long logCount;
}
