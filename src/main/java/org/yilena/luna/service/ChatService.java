package org.yilena.luna.service;

import org.springframework.http.ResponseEntity;
import org.yilena.luna.entity.ChatRequest;

import java.util.List;

public interface ChatService {
    ResponseEntity<Object> chat(ChatRequest chatRequest);

    ResponseEntity<Object> startup();

    void shutdown();

    List<String> getHistoryDate(String yearMonth);

    List<String> getHistory(String yearMonthDay);
}
