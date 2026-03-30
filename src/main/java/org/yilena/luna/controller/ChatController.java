package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.luna.entity.ChatRequest;
import org.yilena.luna.service.ChatService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/luna/api/chat")
@Tag(name = "对话接口")
/**
 * ChatController ??
 */
public class ChatController {

    private final ChatService chatService;

    @Operation(description = "普通对话")
    @PostMapping("/message")
    public ResponseEntity<Object> chat(@RequestBody ChatRequest chatRequest){
        // 转发到对话服务执行主流程。
        return chatService.chat(chatRequest);
    }

    @Operation(description = "开机")
    @PostMapping("/startup")
    public ResponseEntity<Object> startup(){
        // 触发系统启动欢迎与上下文恢复逻辑。
        return chatService.startup();
    }

    @Operation(description = "关机")
    @PostMapping("/shutdown")
    public ResponseEntity<Void> shutdown(){
        // 执行关机前的状态收敛与会话清理。
        chatService.shutdown();
        return ResponseEntity.ok().build();
    }

    @Operation(description = "根据年月获取存在对话历史的日期")
    @GetMapping("/history/date")
    public ResponseEntity<List<String>> getHistoryDate(@RequestParam("ym") String yearMonth){
        // 查询指定年月下存在对话记录的日期集合。
        return ResponseEntity.ok(chatService.getHistoryDate(yearMonth));
    }

    @Operation(description = "根据年月日获取对话历史")
    @GetMapping("/history")
    public ResponseEntity<List<String>> getHistory(@RequestParam("ymd") String yearMonthDay){
        // 查询指定日期的完整对话历史。
        return ResponseEntity.ok(chatService.getHistory(yearMonthDay));
    }
}
