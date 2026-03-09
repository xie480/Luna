package org.yilena.runa.service;

import org.springframework.http.ResponseEntity;
import org.yilena.runa.entity.ChatRequest;

import java.util.List;

public interface ChatService {
    ResponseEntity<String> chat(ChatRequest chatRequest);

    ResponseEntity<String> startup();

    void shutdown();

    List<String> getHistoryDate(String yearMonth);

    List<String> getHistory(String yearMonthDay);
}
