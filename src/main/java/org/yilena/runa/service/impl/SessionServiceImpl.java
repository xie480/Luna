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

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
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
        // 将时间截断到秒级，去掉毫秒部分
        msg.setTime(msg.getTime().truncatedTo(ChronoUnit.SECONDS));
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
    public List<ChatMessage> getRecentMessages(String keyPrefix, boolean isOld) {
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
        if (!isOld && (len > MAX_CHARACTERS || out.size() > MAX_MESSAGES)) {
            // 通知chatService应该要压缩了
            ServiceCommunicateUtil.addSymbol(SymbolConstant.CONTEXT_SUMMARY_FLAG, 1);
        }
        return out;
    }

    public void clearSession(String sessionId) {
        String key = String.format(RedisKeyConstant.CONTEXT_KEY_PREFIX, sessionId);

        List<String> rawList = redis.opsForList().range(key, 0, -1);
        if (rawList == null || rawList.isEmpty()) {
            log.info("清理会话历史：Redis 中不存在会话记录，sessionId={}", sessionId);
            return;
        }

        List<String> retainedMessages = new ArrayList<>();
        int removedCount = 0;

        for (int i = 0; i < rawList.size(); i++) {
            String raw = rawList.get(i);
            try {
                ChatMessage msg = mapper.readValue(raw, ChatMessage.class);
                if (msg == null || msg.getRole() == null) {
                    // 异常数据直接保留，避免误删
                    retainedMessages.add(raw);
                    continue;
                }
                // 仅删除 USER / LUNA
                if (msg.getRole() == ChatMessage.Role.USER || msg.getRole() == ChatMessage.Role.LUNA) {
                    removedCount++;
                    continue;
                }
                retainedMessages.add(raw);
            } catch (Exception e) {
                log.warn("解析 Redis 中的聊天消息失败，已保留该条记录，sessionId={}, index={}, 错误信息={}", sessionId, i, e.getMessage());
                retainedMessages.add(raw);
            }
        }
        // 重写 Redis List
        redis.delete(key);
        if (!retainedMessages.isEmpty()) {
            redis.opsForList().rightPushAll(key, retainedMessages);
        }
        log.info("清理会话历史完成：已删除 USER/LUNA 消息，sessionId={}, 删除条数={}, 剩余条数={}", sessionId, removedCount, retainedMessages.size());
    }



    // 将历史压缩成一条summary
    @Override
    public void replaceHistoryWithSummary(String sessionId, String summary) {
        ChatMessage summaryMsg = new ChatMessage(ChatMessage.Role.CONTEXT_SUMMARY, summary, LocalTime.now());
        log.info("开始用 SUMMARY 替换历史聊天记录");
        clearSession(sessionId);
        appendMessage(sessionId, summaryMsg);
        log.info("历史聊天记录已成功压缩为 SUMMARY");
    }
}
