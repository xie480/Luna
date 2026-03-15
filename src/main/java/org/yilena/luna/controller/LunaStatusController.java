package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.yilena.luna.sse.LunaStatusPublisher;

@RestController
@RequestMapping("/api/luna/status")
@RequiredArgsConstructor
@Tag(name = "Luna 狀態推送接口", description = "用於前端實時獲取 Luna 的內部運行狀態")
public class LunaStatusController {

    private final LunaStatusPublisher statusPublisher;

    @GetMapping(value = "/stream")
    @Operation(summary = "訂閱 Luna 狀態流 (SSE)", description = "建立 SSE 連接，如果已存在連接會自動重連")
    public SseEmitter stream() {
        return statusPublisher.subscribe();
    }

    @GetMapping(value = "/disconnect")
    @Operation(summary = "斷開 Luna 狀態流", description = "主動斷開當前的 SSE 連接")
    public String disconnect() {
        statusPublisher.unsubscribe();
        return "已斷開連接";
    }
}
