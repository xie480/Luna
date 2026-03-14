package org.yilena.luna.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LunaStatusPublisher {

    // 默认的单机客户端ID（对于本地桌面 Agent 通常只有一个前端连接）
    public static final String DEFAULT_CLIENT_ID = "default";

    // 存储客户端连接
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 订阅状态流
     */
    public SseEmitter subscribe() {
        // 设置超时时间为 0（永不超时），或者设置一个较长的时间
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(DEFAULT_CLIENT_ID, emitter);

        emitter.onCompletion(() -> {
            log.info("SSE 连接已完成: {}", DEFAULT_CLIENT_ID);
            emitters.remove(DEFAULT_CLIENT_ID);
        });
        emitter.onTimeout(() -> {
            log.info("SSE 连接已超时: {}", DEFAULT_CLIENT_ID);
            emitters.remove(DEFAULT_CLIENT_ID);
        });
        emitter.onError((e) -> {
            log.error("SSE 连接发生错误: {}", DEFAULT_CLIENT_ID, e);
            emitters.remove(DEFAULT_CLIENT_ID);
        });

        // 发送连接成功初始状态
        publish(DEFAULT_CLIENT_ID, "IDLE", "");
        return emitter;
    }

    /**
     * 发布状态
     */
    public void publish(String DEFAULT_CLIENT_ID, String status, String message) {
        SseEmitter emitter = emitters.get(DEFAULT_CLIENT_ID);
        if (emitter != null) {
            try {
                LunaStatusMessage msg = new LunaStatusMessage(status, message, System.currentTimeMillis());
                emitter.send(SseEmitter.event().name("luna-status").data(msg));
            } catch (Exception e) {
                log.warn("向客户端 {} 推送状态失败，移除连接", DEFAULT_CLIENT_ID);
                emitters.remove(DEFAULT_CLIENT_ID);
            }
        }
    }
}
