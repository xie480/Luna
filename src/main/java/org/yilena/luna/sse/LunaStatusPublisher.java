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
        log.info("----------------SSE 订阅请求: {}", DEFAULT_CLIENT_ID);
        // 设置超时时间为 0（永不超时），或者设置一个较长的时间
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(DEFAULT_CLIENT_ID, emitter);

        // 【重要修复】使用 remove(key, value) 确保只有当前这个 emitter 结束时才移除
        // 防止前端刷新页面时，旧连接的断开回调误删了刚建立的新连接
        emitter.onCompletion(() -> {
            log.info("----------------SSE 连接已完成 (Client Disconnected): {}", DEFAULT_CLIENT_ID);
            emitters.remove(DEFAULT_CLIENT_ID, emitter);
        });
        emitter.onTimeout(() -> {
            log.info("----------------SSE 连接已超时: {}", DEFAULT_CLIENT_ID);
            emitters.remove(DEFAULT_CLIENT_ID, emitter);
        });
        emitter.onError((e) -> {
            log.error("----------------SSE 连接发生错误: {}", DEFAULT_CLIENT_ID, e);
            emitters.remove(DEFAULT_CLIENT_ID, emitter);
        });

        // 发送连接成功初始状态
        publish(DEFAULT_CLIENT_ID, "IDLE", "");
        return emitter;
    }

    /**
     * 发布状态
     */
    public void publish(String clientId, String status, String message) {
        // 使用传入的 clientId，而不是遮蔽静态变量
        SseEmitter emitter = emitters.get(clientId);
        if (emitter != null) {
            try {
                LunaStatusMessage msg = new LunaStatusMessage(status, message, System.currentTimeMillis());
                emitter.send(SseEmitter.event().name("luna-status").data(msg));
                log.info("向客户端 {} 推送状态成功, 状态：{}，msg：{}", clientId, status, message);
            } catch (Exception e) {
                // 只有发送失败（例如客户端断开但回调还没触发）时才会走到这里
                log.warn("向客户端 {} 推送状态失败，移除连接", clientId);
                emitters.remove(clientId, emitter);
            }
        } else {
            log.debug("客户端 {} 未连接，跳过推送", clientId);
        }
    }
}
