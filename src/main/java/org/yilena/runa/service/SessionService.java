package org.yilena.runa.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.runa.constants.RedisKeyConstant;
import org.yilena.runa.entity.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public interface SessionService {
    void appendMessage(String userId, ChatMessage chatMessage);

    List<ChatMessage> getRecentMessages(String userId, int i);

    void replaceHistoryWithSummary(String prefix, String summary);
}
