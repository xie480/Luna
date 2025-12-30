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

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    // 每次会话最多保留最近50条信息
    private static final int MAX_MESSAGES = 50;
    // 会话TTL
    private static final long SESSION_TTL_SECONDS = 30 * 60;
    // 会话上下文最大字数
    private static final int MAX_CONTEXT_CHARS = 5000;

    public void appendMessage(String sessionId, ChatMessage msg) {
        try {
            // 转为JSON
            String json = mapper.writeValueAsString(msg);
            String key = String.format(RedisKeyConstant.SESSION_KEY_PREFIX, sessionId);
            // todo 这里要使用lua
            // 加入上下文
            redis.opsForList().rightPush(key, json);
            // 保证长度
            redis.opsForList().trim(key, -MAX_MESSAGES, -1);
            // todo 刷新TTL
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public List<ChatMessage> getRecentMessages(String sessionId, int maxItems) {
        String key = String.format(RedisKeyConstant.SESSION_KEY_PREFIX, sessionId);
        // 获取最近N条
        long start = Math.max(-maxItems, -MAX_MESSAGES);
        List<String> jsons = redis.opsForList().range(key, start, -1);
        if (jsons == null){
            return Collections.emptyList();
        }
        // 计算长度
        int len = 0;
        // 反序列化
        List<ChatMessage> out = new ArrayList<>(jsons.size());
        for (String s : jsons) {
            try {
                len += s.length();
                out.add(mapper.readValue(s, ChatMessage.class));
            } catch (JsonProcessingException ex) {
                log.error("解析数据失败: {}", s, ex);
            }
        }
        return out;
    }

    public void clearSession(String sessionId) {
        redis.delete(String.format(RedisKeyConstant.SESSION_KEY_PREFIX, sessionId));
    }

    // 将历史压缩成一条summary
    public void replaceHistoryWithSummary(String sessionId, String summary) {
        String key = String.format(RedisKeyConstant.SESSION_KEY_PREFIX, sessionId);
        redis.delete(key);
        ChatMessage summaryMsg = new ChatMessage(ChatMessage.Role.SYSTEM, "[SUMMARY] " + summary);
        clearSession(sessionId);
        appendMessage(sessionId, summaryMsg);
    }
}
