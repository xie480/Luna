package org.yilena.luna.utils;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文裁剪工具类
 * 当 Prompt 总长度超过限制时，根据优先级淘汰信息
 * 
 * 淘汰优先级：
 * 1. 长期记忆 (LongTermMemory)
 * 2. 日程提醒 (ScheduleReminders)
 * 3. 用户偏好 (UserPreferences)
 * 4. 知识库 (RAG / KnowledgeBase)
 * 5. 最近聊天记录 (RecentChatHistory)
 * 6. 当前用户输入 (UserInput) - 尽量保留
 * 7. System Prompt - 尽量保留
 */
@Slf4j
public class ContextPruner {

    // 默认最大字符数限制 (可根据实际模型 Context Window 调整，如 32k token 约 10万字符，这里保守设为 20000)
    public static final int MAX_PROMPT_CHARS = 60000;

    @Data
    @Builder
    public static class ContextPayload {
        // 优先级 7 (最后淘汰)
        private String systemPrompt;
        // 优先级 6
        private String userInput;
        // 优先级 5
        private List<String> recentChatHistory;
        // 优先级 4
        private List<String> knowledgeBase;
        // 优先级 3
        private List<String> userPreferences;
        // 优先级 2
        private List<String> scheduleReminders;
        // 优先级 1 (最先淘汰)
        private List<String> longTermMemory;
    }

    /**
     * 执行裁剪
     * @param payload 上下文负载
     * @return 裁剪后的负载
     */
    public static ContextPayload prune(ContextPayload payload) {
        return prune(payload, MAX_PROMPT_CHARS);
    }

    /**
     * 执行裁剪 (指定最大长度)
     */
    public static ContextPayload prune(ContextPayload payload, int maxChars) {
        long currentLength = calculateTotalLength(payload);
        
        if (currentLength <= maxChars) {
            return payload;
        }

        log.info("Prompt 总长度 {} 超过限制 {}，开始执行上下文裁剪...", currentLength, maxChars);

        // 1. 淘汰长期记忆
        if (isNotEmpty(payload.getLongTermMemory())) {
            log.info("裁剪策略：移除长期记忆");
            payload.setLongTermMemory(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) return payload;
        }

        // 2. 淘汰日程提醒
        if (isNotEmpty(payload.getScheduleReminders())) {
            log.info("裁剪策略：移除日程提醒");
            payload.setScheduleReminders(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) return payload;
        }

        // 3. 淘汰用户偏好
        if (isNotEmpty(payload.getUserPreferences())) {
            log.info("裁剪策略：移除用户偏好");
            payload.setUserPreferences(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) return payload;
        }

        // 4. 淘汰知识库 (RAG)
        if (isNotEmpty(payload.getKnowledgeBase())) {
            log.info("裁剪策略：移除知识库(RAG)");
            payload.setKnowledgeBase(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) return payload;
        }

        // 5. 淘汰最近聊天记录 (保留最近的，移除旧的)
        if (isNotEmpty(payload.getRecentChatHistory())) {
            log.info("裁剪策略：压缩最近聊天记录");
            List<String> history = new ArrayList<>(payload.getRecentChatHistory());
            // 假设 list 尾部是最新消息，头部是最旧消息
            // 逐步移除头部，直到满足长度要求
            while (!history.isEmpty() && calculateTotalLength(payload) > maxChars) {
                history.remove(0);
                payload.setRecentChatHistory(history);
            }
            if (calculateTotalLength(payload) <= maxChars) return payload;
        }

        // 6. 当前用户输入 (通常不裁剪，除非极端情况)
        // 7. System Prompt (不裁剪)

        log.warn("裁剪后长度 {} 仍超过限制 {}，保留核心 SystemPrompt 和 UserInput", calculateTotalLength(payload), maxChars);
        return payload;
    }

    private static boolean isNotEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * 计算总长度 (估算值)
     */
    private static long calculateTotalLength(ContextPayload p) {
        long len = 0;
        // 估算 System Prompt
        if (p.getSystemPrompt() != null) len += p.getSystemPrompt().length();
        // 估算 User Input
        if (p.getUserInput() != null) len += p.getUserInput().length();
        
        // 列表内容 + 换行符估算
        len += listLength(p.getRecentChatHistory());
        len += listLength(p.getKnowledgeBase());
        len += listLength(p.getUserPreferences());
        len += listLength(p.getScheduleReminders());
        len += listLength(p.getLongTermMemory());
        
        // 额外预留一些 buffer (例如 Prompt 模板中的固定字符、JSON 结构字符等)
        len += 1000; 
        
        return len;
    }

    private static long listLength(List<String> list) {
        if (list == null) return 0;
        long l = 0;
        for (String s : list) {
            if (s != null) l += s.length();
        }
        return l;
    }
}
