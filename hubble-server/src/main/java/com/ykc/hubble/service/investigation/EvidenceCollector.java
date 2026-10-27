package com.ykc.hubble.service.investigation;

/**
 * 取证采集器统一接口。实现方必须内部兜底：任何异常转为 degraded 证据返回，绝不抛出。
 */
public interface EvidenceCollector {

    String name();

    Evidence collect(InvestigationContext ctx);
}
