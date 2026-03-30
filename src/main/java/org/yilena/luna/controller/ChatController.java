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

    private final ChatService chatService; // 声明成员字段

    @Operation(description = "普通对话") // 声明注解
    @PostMapping("/message") // 声明注解
    public ResponseEntity<Object> chat(@RequestBody ChatRequest chatRequest){ // 定义方法签名
        return chatService.chat(chatRequest); // 返回处理结果
    } // 结束当前代码块

    @Operation(description = "开机") // 声明注解
    @PostMapping("/startup") // 声明注解
    public ResponseEntity<Object> startup(){ // 定义方法签名
        return chatService.startup(); // 返回处理结果
    } // 结束当前代码块

    @Operation(description = "关机") // 声明注解
    @PostMapping("/shutdown") // 声明注解
    public ResponseEntity<Void> shutdown(){ // 定义方法签名
        chatService.shutdown(); // 执行语句逻辑
        return ResponseEntity.ok().build(); // 返回处理结果
    } // 结束当前代码块

    @Operation(description = "根据年月获取存在对话历史的日期") // 声明注解
    @GetMapping("/history/date") // 声明注解
    public ResponseEntity<List<String>> getHistoryDate(@RequestParam("ym") String yearMonth){ // 定义方法签名
        return ResponseEntity.ok(chatService.getHistoryDate(yearMonth)); // 返回处理结果
    } // 结束当前代码块

    @Operation(description = "根据年月日获取对话历史") // 声明注解
    @GetMapping("/history") // 声明注解
    public ResponseEntity<List<String>> getHistory(@RequestParam("ymd") String yearMonthDay){ // 定义方法签名
        return ResponseEntity.ok(chatService.getHistory(yearMonthDay)); // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
