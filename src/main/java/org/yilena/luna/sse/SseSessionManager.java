package org.yilena.luna.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 会话管理器，负责维护客户端连接、断开连接、发送消息和在线状态判断。
 */
@Slf4j
@Component
public class SseSessionManager {

    /**
     * 当前在线客户端与 SSE 发射器的映射关系。
     */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * SSE 默认超时时间，单位为毫秒，这里设置为 24 小时。
     */
    private static final long DEFAULT_TIMEOUT = 1000L * 60 * 60 * 24;

    /**
     * 建立指定客户端的 SSE 连接，若已存在旧连接则先主动断开。
     */
    public SseEmitter connect(String clientId) {
        /**
         * 同一客户端重复连接时先清理旧连接，避免旧发射器残留导致推送异常。
         */
        if (emitters.containsKey(clientId)) {
            log.info("检测到客户端 {} 旧连接尚存，正在断开以进行重连...", clientId);
            disconnect(clientId);
        }

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        emitters.put(clientId, emitter);
        log.info("SSE 连接建立成功: {}", clientId);

        /**
         * 为连接注册完成、超时和异常回调，确保会话表可以及时清理。
         */
        emitter.onCompletion(() -> {
            log.info("SSE 连接已完成(Client Disconnected): {}", clientId);
            emitters.remove(clientId, emitter);
        });

        emitter.onTimeout(() -> {
            log.info("SSE 连接已超时: {}", clientId);
            emitter.complete();
            emitters.remove(clientId, emitter);
        });

        emitter.onError((e) -> {
            log.error("SSE 连接发生异常: {}", clientId, e);
            emitter.completeWithError(e);
            emitters.remove(clientId, emitter);
        });

        return emitter;
    }

    /**
     * 主动断开指定客户端连接。
     */
    public void disconnect(String clientId) {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter != null) {
            log.info("主动断开客户端连接: {}", clientId);
            emitter.complete();
            emitters.remove(clientId);
        }
    }

    /**
     * 向指定客户端发送 SSE 消息，发送失败时自动移除失效连接。
     */
    public boolean send(String clientId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                return true;
            } catch (Exception e) {
                log.warn("向客户端 {} 发送消息失败，移除连接", clientId);
                disconnect(clientId);
                return false;
            }
        }
        return false;
    }

    /**
     * 判断指定客户端当前是否仍然在线。
     */
    public boolean isConnected(String clientId) {
        return emitters.containsKey(clientId);
    }
}
