package org.yilena.luna.utils;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 该工具类用于在提示词过长时按优先级裁剪上下文内容，优先保留系统提示和当前用户输入。
 */
@Slf4j
public class ContextPruner {

    /**
     * 默认允许的提示词最大字符数。
     */
    public static final int MAX_PROMPT_CHARS = 60000;

    /**
     * 该载体用于描述待裁剪的上下文各组成部分，并按优先级区分可被裁剪的内容来源。
     */
    @Data
    @Builder
    public static class ContextPayload {
        /**
         * 系统提示词，优先级最高，尽量保留。
         */
        private String systemPrompt;
        /**
         * 当前用户输入，优先级较高，尽量保留。
         */
        private String userInput;
        /**
         * 最近聊天记录。
         */
        private List<String> recentChatHistory;
        /**
         * 知识库检索内容。
         */
        private List<String> knowledgeBase;
        /**
         * 用户偏好信息。
         */
        private List<String> userPreferences;
        /**
         * 日程提醒信息。
         */
        private List<String> scheduleReminders;
        /**
         * 长期记忆内容，优先被裁剪。
         */
        private List<String> longTermMemory;
    }

    /**
     * 按默认最大长度执行上下文裁剪。
     */
    public static ContextPayload prune(ContextPayload payload) {
        return prune(payload, MAX_PROMPT_CHARS);
    }

    /**
     * 按指定最大长度执行上下文裁剪，逐步移除低优先级内容直到满足长度限制。
     */
    public static ContextPayload prune(ContextPayload payload, int maxChars) {
        long currentLength = calculateTotalLength(payload);

        if (currentLength <= maxChars) {
            return payload;
        }

        log.info("Prompt 总长度 {} 超过限制 {}，开始执行上下文裁剪...", currentLength, maxChars);

        /**
         * 先从长期记忆开始裁剪，优先移除对当前轮次影响最弱的历史信息。
         */
        if (isNotEmpty(payload.getLongTermMemory())) {
            log.info("裁剪策略：移除长期记忆");
            payload.setLongTermMemory(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) {
                return payload;
            }
        }

        /**
         * 再移除日程提醒，避免非核心提醒内容挤占主任务上下文。
         */
        if (isNotEmpty(payload.getScheduleReminders())) {
            log.info("裁剪策略：移除日程提醒");
            payload.setScheduleReminders(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) {
                return payload;
            }
        }

        /**
         * 用户偏好属于辅助信息，长度仍超限时继续裁剪。
         */
        if (isNotEmpty(payload.getUserPreferences())) {
            log.info("裁剪策略：移除用户偏好");
            payload.setUserPreferences(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) {
                return payload;
            }
        }

        /**
         * 再裁剪知识库内容，保留对当前对话最刚需的输入和指令信息。
         */
        if (isNotEmpty(payload.getKnowledgeBase())) {
            log.info("裁剪策略：移除知识库内容");
            payload.setKnowledgeBase(Collections.emptyList());
            if (calculateTotalLength(payload) <= maxChars) {
                return payload;
            }
        }

        /**
         * 最后压缩最近聊天记录，优先保留最新消息，逐步移除更早的对话。
         */
        if (isNotEmpty(payload.getRecentChatHistory())) {
            log.info("裁剪策略：压缩最近聊天记录");
            List<String> history = new ArrayList<>(payload.getRecentChatHistory());
            while (!history.isEmpty() && calculateTotalLength(payload) > maxChars) {
                history.remove(0);
                payload.setRecentChatHistory(history);
            }
            if (calculateTotalLength(payload) <= maxChars) {
                return payload;
            }
        }

        /**
         * 到这里仍超限时，只保留最核心的系统提示和当前输入，由调用方决定是否继续降级。
         */
        log.warn("裁剪后长度 {} 仍超过限制 {}，仅保留核心 SystemPrompt 和 UserInput", calculateTotalLength(payload), maxChars);
        return payload;
    }

    private static boolean isNotEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * 估算上下文总长度，用于决定是否需要继续裁剪。
     */
    private static long calculateTotalLength(ContextPayload p) {
        long len = 0;
        if (p.getSystemPrompt() != null) {
            len += p.getSystemPrompt().length();
        }
        if (p.getUserInput() != null) {
            len += p.getUserInput().length();
        }

        len += listLength(p.getRecentChatHistory());
        len += listLength(p.getKnowledgeBase());
        len += listLength(p.getUserPreferences());
        len += listLength(p.getScheduleReminders());
        len += listLength(p.getLongTermMemory());

        /**
         * 预留固定 buffer，用于覆盖模板包装字符和结构化输出带来的额外长度。
         */
        len += 1000;

        return len;
    }

    private static long listLength(List<String> list) {
        if (list == null) {
            return 0;
        }
        long l = 0;
        for (String s : list) {
            if (s != null) {
                l += s.length();
            }
        }
        return l;
    }
}
