package org.yilena.luna.sse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Luna 狀態發布器
 * 專注於業務邏輯，調用 SseSessionManager 進行底層通信
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LunaStatusPublisher {

    private final SseSessionManager sessionManager;

    // 默認的單機客戶端ID
    public static final String DEFAULT_CLIENT_ID = "default";

    /**
     * 訂閱狀態流
     */
    public SseEmitter subscribe() {
        log.info("----------------SSE 訂閱請求: {}", DEFAULT_CLIENT_ID);

        SseEmitter emitter = sessionManager.connect(DEFAULT_CLIENT_ID);

        // 初始状态
        publish(DEFAULT_CLIENT_ID, "IDLE", "");

        return emitter;
    }

    /**
     * 斷開訂閱
     */
    public void unsubscribe() {
        sessionManager.disconnect(DEFAULT_CLIENT_ID);
    }

    /**
     * 发布普通状态（兼容旧调用）
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
     * 统一事件发布
     */
    public void publishEvent(String clientId, String eventType, Object payload) {
        if (sessionManager.isConnected(clientId)) {
            boolean success = sessionManager.send(clientId, eventType, payload);
            if (success) {
                log.info("向客戶端 {} 推送事件成功, eventType={}", clientId, eventType);
            }
        } else {
            log.debug("客戶端 {} 未連接，跳過推送 eventType={}", clientId, eventType);
        }
    }
}
