package org.yilena.runa.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yilena.runa.entity.ChatRequest;
import org.yilena.runa.service.ChatService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/llm/api/chat")
@Tag(name = "对话接口")
public class ChatController {

    private final ChatService chatService;

    @Operation(description = "普通对话")
    @PostMapping("/message")
    public ResponseEntity<String> chat(@RequestBody ChatRequest chatRequest){
        return chatService.chat(chatRequest);
    }
}
