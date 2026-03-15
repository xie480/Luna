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
        
        // 調用管理器建立連接（管理器內部會處理舊連接清理）
        SseEmitter emitter = sessionManager.connect(DEFAULT_CLIENT_ID);

        // 發送連接成功初始狀態
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
     * 發布狀態
     */
    public void publish(String clientId, String status, String message) {
        if (sessionManager.isConnected(clientId)) {
            LunaStatusMessage msg = new LunaStatusMessage(status, message, System.currentTimeMillis());
            boolean success = sessionManager.send(clientId, "luna-status", msg);
            if (success) {
                log.info("向客戶端 {} 推送狀態成功, 狀態：{}，msg：{}", clientId, status, message);
            }
        } else {
            log.debug("客戶端 {} 未連接，跳過推送", clientId);
        }
    }
}
