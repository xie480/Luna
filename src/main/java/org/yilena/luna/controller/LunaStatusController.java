package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.yilena.luna.sse.LunaStatusPublisher;

@RestController
@RequestMapping("/api/luna/status")
@RequiredArgsConstructor
@Tag(name = "Luna 状态推送接口", description = "用于前端通过 SSE 实时订阅 Luna 的运行状态")
/**
 * Luna 状态控制器，负责维护前端与服务端之间的 SSE 状态订阅通道。
 */
public class LunaStatusController {

    /**
     * 状态发布器，负责创建和维护 SSE 订阅。
     */
    private final LunaStatusPublisher statusPublisher;

    @GetMapping(value = "/stream", produces = MediaType.ALL_VALUE)
    /**
     * 建立 SSE 长连接并订阅 Luna 运行状态。
     *
     * 该接口会主动设置响应头，确保浏览器或前端在不同 Accept 头场景下都能正确建立事件流连接。
     */
    @Operation(summary = "订阅运行状态流", description = "建立 SSE 连接并持续接收 Luna 的运行状态推送")
    public SseEmitter stream(HttpServletResponse response) {
        /**
         * 手动设置 SSE 所需响应头，避免因为内容协商或代理缓存导致连接无法正常建立。
         */
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");

        return statusPublisher.subscribe();
    }

    @GetMapping(value = "/disconnect")
    /**
     * 主动断开当前默认客户端的 SSE 订阅连接。
     */
    @Operation(summary = "断开状态流", description = "主动关闭当前客户端的 SSE 状态订阅连接")
    public String disconnect() {
        statusPublisher.unsubscribe();
        return "已断开连接";
    }
}
