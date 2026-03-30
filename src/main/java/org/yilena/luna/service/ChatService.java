package org.yilena.luna.service; // define package

import org.springframework.http.ResponseEntity; // import dependency
import org.yilena.luna.entity.ChatRequest; // import dependency

import java.util.List; // import dependency

public interface ChatService { // define interface
    ResponseEntity<Object> chat(ChatRequest chatRequest); // business logic

    ResponseEntity<Object> startup(); // business logic

    void shutdown(); // business logic

    List<String> getHistoryDate(String yearMonth); // business logic

    List<String> getHistory(String yearMonthDay); // business logic
} // block end
