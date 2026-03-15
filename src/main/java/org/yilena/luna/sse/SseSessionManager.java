package org.yilena.luna.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 會話管理器
 * 負責底層的連接管理、發送消息、斷開連接等通用邏輯
 */
@Slf4j
@Component
public class SseSessionManager {

    // 存儲客戶端連接
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // 超時時間設置為 1 天
    private static final long DEFAULT_TIMEOUT = 1000L * 60 * 60 * 24;

    /**
     * 建立連接
     * 如果已存在同名客戶端，會先斷開舊連接（支持重連）
     *
     * @param clientId 客戶端標識
     * @return SseEmitter
     */
    public SseEmitter connect(String clientId) {
        // 如果已存在連接，先移除舊的，確保新連接能正常建立（解決刷新頁面問題）
        if (emitters.containsKey(clientId)) {
            log.info("檢測到客戶端 {} 舊連接尚存，正在斷開以進行重連...", clientId);
            disconnect(clientId);
        }

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        emitters.put(clientId, emitter);
        log.info("SSE 連接建立成功: {}", clientId);

        // 註冊回調
        emitter.onCompletion(() -> {
            log.info("SSE 連接已完成 (Client Disconnected): {}", clientId);
            // 只有當 map 中的對象是當前這個 emitter 時才移除，防止並發誤刪新連接
            emitters.remove(clientId, emitter);
        });

        emitter.onTimeout(() -> {
            log.info("SSE 連接已超時: {}", clientId);
            emitter.complete();
            emitters.remove(clientId, emitter);
        });

        emitter.onError((e) -> {
            log.error("SSE 連接發生錯誤: {}", clientId, e);
            emitter.completeWithError(e);
            emitters.remove(clientId, emitter);
        });

        return emitter;
    }

    /**
     * 主動斷開連接
     *
     * @param clientId 客戶端標識
     */
    public void disconnect(String clientId) {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter != null) {
            log.info("主動斷開客戶端連接: {}", clientId);
            emitter.complete();
            emitters.remove(clientId);
        }
    }

    /**
     * 發送數據給指定客戶端
     *
     * @param clientId 客戶端標識
     * @param eventName 事件名稱
     * @param data 數據對象
     * @return 是否發送成功
     */
    public boolean send(String clientId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                return true;
            } catch (Exception e) {
                log.warn("向客戶端 {} 發送消息失敗，移除連接", clientId);
                disconnect(clientId);
                return false;
            }
        }
        return false;
    }

    /**
     * 檢查客戶端是否在線
     */
    public boolean isConnected(String clientId) {
        return emitters.containsKey(clientId);
    }
}
