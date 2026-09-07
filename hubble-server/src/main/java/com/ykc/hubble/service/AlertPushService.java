package com.ykc.hubble.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时告警推送：维护 SSE 订阅者，采集到红盘时广播。
 * <p>
 * 独立于轮询通道——大盘轮询读缓存拿整体状态，告警推送只负责“即时闪红”。
 *
 * @author Cloud Eyes Team
 */
@Slf4j
@Service
public class AlertPushService {

    /** SSE 连接超时时间（10 分钟，到期后前端重连） */
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 订阅实时告警
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /**
     * 向所有订阅者广播一条告警事件
     */
    public void pushAlert(Map<String, Object> payload) {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("alert").data(payload));
            } catch (IOException | IllegalStateException e) {
                // 连接已断，移除
                emitters.remove(emitter);
            }
        }
    }
}
