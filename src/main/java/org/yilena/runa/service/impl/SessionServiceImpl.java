package org.yilena.runa.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.runa.constants.RedisKeyConstant;
import org.yilena.runa.constants.SymbolConstant;
import org.yilena.runa.entity.ChatMessage;
import org.yilena.runa.service.SessionService;
import org.yilena.runa.utils.ServiceCommunicateUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class SessionServiceImpl implements SessionService {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    // 每次会话最多保留最近50条信息
    private static final int MAX_MESSAGES = 50;
    // 单日会话最大字数
    private static final int MAX_CHARACTERS = 10000;

    @Override
    public void appendMessage(String keyPrefix, ChatMessage msg) {
        try {
            // 转为JSON
            String json = mapper.writeValueAsString(msg);
            String key = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, keyPrefix);
            // 加入上下文
            redis.opsForList().rightPush(key, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<ChatMessage> getRecentMessages(String keyPrefix, int maxItems) {
        String key = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, keyPrefix);
        // 获取最近N条
        List<String> jsons = redis.opsForList().range(key, 0, -1);
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
        if (len > MAX_CHARACTERS || out.size() > MAX_MESSAGES) {
            // 通知chatService应该要压缩了
            ServiceCommunicateUtil.addSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG, 1);
        }
        return out;
    }

    public void clearSession(String sessionId) {
        redis.delete(String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, sessionId));
    }

    // 将历史压缩成一条summary
    @Override
    public void replaceHistoryWithSummary(String sessionId, String summary) {
        // 添加新的
        ChatMessage summaryMsg = new ChatMessage(ChatMessage.Role.SYSTEM, "[SUMMARY] " + summary);
        // 清空历史
        clearSession(sessionId);
        appendMessage(sessionId, summaryMsg);
    }
}
