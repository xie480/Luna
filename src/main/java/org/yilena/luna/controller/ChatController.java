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
@Tag(name = "对话接口", description = "提供聊天、会话启动、关闭和历史查询能力")
/**
 * 对话控制器，负责承接前端聊天请求并协调会话生命周期相关接口。
 */
public class ChatController {

    /**
     * 对话服务，负责执行聊天主流程。
     */
    private final ChatService chatService;

    /**
     * 提交一轮用户消息并触发完整的对话处理链路。
     */
    @Operation(summary = "发送对话消息", description = "接收用户输入并触发模型推理、上下文拼装和回复生成流程")
    @PostMapping("/message")
    public ResponseEntity<Object> chat(@RequestBody ChatRequest chatRequest){
        /**
         * 将请求转交给对话服务执行主流程，控制层只负责协议适配。
         */
        return chatService.chat(chatRequest);
    }

    /**
     * 触发系统启动阶段的欢迎语和上下文恢复逻辑。
     */
    @Operation(summary = "启动会话", description = "执行启动欢迎、状态恢复等初始化流程")
    @PostMapping("/startup")
    public ResponseEntity<Object> startup(){
        /**
         * 交由对话服务执行启动阶段逻辑，便于统一管理会话状态。
         */
        return chatService.startup();
    }

    /**
     * 在系统关闭前执行会话收尾和状态清理。
     */
    @Operation(summary = "关闭会话", description = "执行对话结束前的状态落库、资源释放和会话清理")
    @PostMapping("/shutdown")
    public ResponseEntity<Void> shutdown(){
        /**
         * 调用服务层完成关机前的收尾动作，避免遗留运行态数据。
         */
        chatService.shutdown();
        return ResponseEntity.ok().build();
    }

    /**
     * 查询指定年月下存在聊天记录的日期列表，便于前端按日展示历史入口。
     */
    @Operation(summary = "查询历史日期", description = "根据年月返回存在对话记录的日期集合")
    @GetMapping("/history/date")
    public ResponseEntity<List<String>> getHistoryDate(@RequestParam("ym") String yearMonth){
        /**
         * 返回指定年月下有聊天记录的日期，供历史面板按天检索。
         */
        return ResponseEntity.ok(chatService.getHistoryDate(yearMonth));
    }

    /**
     * 查询指定日期的完整对话历史，供前端回放历史内容。
     */
    @Operation(summary = "查询对话历史", description = "根据年月日返回当天的完整对话记录")
    @GetMapping("/history")
    public ResponseEntity<List<String>> getHistory(@RequestParam("ymd") String yearMonthDay){
        /**
         * 将日期参数透传给服务层，统一由服务层读取历史记录。
         */
        return ResponseEntity.ok(chatService.getHistory(yearMonthDay));
    }
}
