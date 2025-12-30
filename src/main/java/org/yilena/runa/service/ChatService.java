package org.yilena.runa.service;

import org.springframework.http.ResponseEntity;
import org.yilena.runa.entity.ChatRequest;

public interface ChatService {
    ResponseEntity<String> chat(ChatRequest chatRequest);
}
