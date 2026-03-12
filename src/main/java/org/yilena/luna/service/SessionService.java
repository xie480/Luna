package org.yilena.luna.service;

import org.yilena.luna.entity.ChatMessage;

import java.util.List;


public interface SessionService {
    void appendMessage(String userId, ChatMessage chatMessage);

    List<ChatMessage> getRecentMessages(String userId, boolean isOld);

    void replaceHistoryWithSummary(String prefix, String summary);
}
