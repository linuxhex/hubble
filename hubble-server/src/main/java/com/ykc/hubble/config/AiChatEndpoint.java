package com.ykc.hubble.config;

import com.ykc.hubble.service.AiChatService;
import com.ykc.hubble.util.JwtUtil;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ServerEndpoint("/api/ws/ai/chat")
public class AiChatEndpoint {

    private static AiChatService aiChatService;
    private static JwtUtil jwtUtil;

    @Autowired
    public void setAiChatService(AiChatService aiChatService) {
        AiChatEndpoint.aiChatService = aiChatService;
    }

    @Autowired
    public void setJwtUtil(JwtUtil jwtUtil) {
        AiChatEndpoint.jwtUtil = jwtUtil;
    }

    private static final Map<String, Session> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        String token = extractToken(session);
        if (!StringUtils.hasText(token) || !jwtUtil.validateToken(token)) {
            log.warn("AI Chat WS 认证失败, sessionId={}", session.getId());
            sendJson(session, Map.of("type", "error", "message", "认证失败，请重新登录"));
            closeQuietly(session, new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "认证失败"));
            return;
        }

        ACTIVE_SESSIONS.put(session.getId(), session);
        log.info("AI Chat WS 连接建立, sessionId={}", session.getId());
        sendJson(session, Map.of("type", "status", "message", "连接成功"));
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        if (!message.startsWith("{")) {
            sendJson(session, Map.of("type", "error", "message", "消息格式错误"));
            return;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(message);
            String action = node.has("action") ? node.get("action").asText() : "chat";
            String userMessage = node.has("message") ? node.get("message").asText() : "";

            if ("clear".equals(action)) {
                aiChatService.clearSession(session.getId());
                sendJson(session, Map.of("type", "status", "message", "对话已清空"));
                return;
            }

            if (!StringUtils.hasText(userMessage)) {
                sendJson(session, Map.of("type", "error", "message", "消息不能为空"));
                return;
            }

            sendJson(session, Map.of("type", "status", "message", "thinking"));

            aiChatService.chat(session.getId(), userMessage, new AiChatService.ChunkCallback() {
                @Override
                public void onChunk(String text) {
                    sendJson(session, Map.of("type", "chunk", "content", text));
                }

                @Override
                public void onToolStatus(String status) {
                    sendJson(session, Map.of("type", "tool_status", "content", status));
                }

                @Override
                public void onComplete() {
                    sendJson(session, Map.of("type", "done"));
                }

                @Override
                public void onError(String errorMessage) {
                    sendJson(session, Map.of("type", "error", "message", errorMessage));
                }
            });
        } catch (Exception e) {
            log.error("AI Chat 消息处理异常: {}", e.getMessage(), e);
            sendJson(session, Map.of("type", "error", "message", "消息处理异常"));
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        ACTIVE_SESSIONS.remove(session.getId());
        log.info("AI Chat WS 连接关闭, sessionId={}, reason={}", session.getId(), closeReason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        ACTIVE_SESSIONS.remove(session.getId());
        log.error("AI Chat WS 异常, sessionId={}: {}", session.getId(), throwable.getMessage());
    }

    private String extractToken(Session session) {
        String queryString = session.getRequestURI().getQuery();
        if (queryString != null) {
            for (String param : queryString.split("&")) {
                String[] kv = param.split("=", 2);
                if ("token".equals(kv[0]) && kv.length == 2) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private void sendJson(Session session, Map<String, String> data) {
        if (!session.isOpen()) return;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            session.getBasicRemote().sendText(mapper.writeValueAsString(data));
        } catch (IOException e) {
            log.error("WS 消息发送失败: {}", e.getMessage());
        }
    }

    private void closeQuietly(Session session, CloseReason reason) {
        try {
            session.close(reason);
        } catch (IOException ignored) {}
    }
}
