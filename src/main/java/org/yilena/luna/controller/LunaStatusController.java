package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.yilena.luna.sse.LunaStatusPublisher;

@RestController
@RequestMapping("/api/luna/status")
@RequiredArgsConstructor
@Tag(name = "Luna 状态推送接口", description = "用于前端实时获取 Luna 的内部运行状态")
public class LunaStatusController {

    private final LunaStatusPublisher statusPublisher;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅 Luna 状态流 (SSE)")
    public SseEmitter stream(@RequestParam(defaultValue = LunaStatusPublisher.DEFAULT_CLIENT_ID) String clientId) {
        return statusPublisher.subscribe(clientId);
    }
}
