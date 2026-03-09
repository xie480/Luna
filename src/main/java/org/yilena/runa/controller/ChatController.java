package org.yilena.runa.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.runa.entity.ChatRequest;
import org.yilena.runa.service.ChatService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/luna/api/chat")
@Tag(name = "对话接口")
public class ChatController {

    private final ChatService chatService;

    @Operation(description = "普通对话")
    @PostMapping("/message")
    public ResponseEntity<String> chat(@RequestBody ChatRequest chatRequest){
        return chatService.chat(chatRequest);
    }

    @Operation(description = "开机")
    @PostMapping("/startup")
    public ResponseEntity<String> startup(){
        return chatService.startup();
    }

    @Operation(description = "关机")
    @PostMapping("/shutdown")
    public ResponseEntity<Void> shutdown(){
        chatService.shutdown();
        return ResponseEntity.ok().build();
    }

    @Operation(description = "根据年月获取存在对话历史的日期")
    @GetMapping("/history/date")
    public ResponseEntity<List<String>> getHistoryDate(@RequestParam("ym") String yearMonth){
        return ResponseEntity.ok(chatService.getHistoryDate(yearMonth));
    }

    @Operation(description = "根据年月日获取对话历史")
    @GetMapping("/history")
    public ResponseEntity<List<String>> getHistory(@RequestParam("ymd") String yearMonthDay){
        return ResponseEntity.ok(chatService.getHistory(yearMonthDay));
    }
}
