package org.yilena.luna.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Luna 状态发布器，负责对外提供默认状态流订阅、取消订阅和统一事件推送入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LunaStatusPublisher {

    /**
     * SSE 会话管理器，用于维护连接和真正发送事件。
     */
    private final SseSessionManager sessionManager;

    /**
     * 默认客户端标识，适用于单用户状态流场景。
     */
    public static final String DEFAULT_CLIENT_ID = "default";

    /**
     * 建立默认状态流订阅，并在连接成功后主动推送一次初始状态。
     */
    public SseEmitter subscribe() {
        log.info("----------------SSE 订阅请求: {}", DEFAULT_CLIENT_ID);

        /**
         * 建立 SSE 连接后立即下发初始状态，便于前端拿到稳定的起始值。
         */
        SseEmitter emitter = sessionManager.connect(DEFAULT_CLIENT_ID);
        publish(DEFAULT_CLIENT_ID, "IDLE", "");
        return emitter;
    }

    /**
     * 取消默认状态流订阅。
     */
    public void unsubscribe() {
        sessionManager.disconnect(DEFAULT_CLIENT_ID);
    }

    /**
     * 发布普通状态消息，兼容旧版状态更新调用方式。
     */
    public void publish(String clientId, String status, String message) {
        LunaStatusMessage msg = new LunaStatusMessage(
                "luna-status",
                status,
                message,
                "",
                "",
                System.currentTimeMillis()
        );
        publishEvent(clientId, "luna-status", msg);
    }

    /**
     * 发布统一 SSE 事件，底层复用会话管理器完成实际发送。
     */
    public void publishEvent(String clientId, String eventType, Object payload) {
        /**
         * 仅在客户端仍然在线时发送事件，避免无效推送和无意义异常日志。
         */
        if (sessionManager.isConnected(clientId)) {
            boolean success = sessionManager.send(clientId, eventType, payload);
            if (success) {
                log.info("向客户端 {} 推送事件成功, eventType={}", clientId, eventType);
            }
        } else {
            log.debug("客户端 {} 未连接，跳过推送, eventType={}", clientId, eventType);
        }
    }
}
