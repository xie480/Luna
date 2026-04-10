package org.yilena.luna.memory.impl;

import org.springframework.stereotype.Component;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.SessionType;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.Locale;

@Component
/**
 * 会话类型解析器，负责在任务会话、陪伴会话和混合会话之间做动态判定，
 * 为提示策略和上下文预算分配提供依据。
 */
public class SessionTypeResolver {

    public SessionType resolve(String userInput,
                               String eventType,
                               String payloadJson,
                               TaskRuntimeState taskState,
                               RelationalRuntimeState relationalState,
                               SessionType previousType) {
        /**
         * 先分别计算任务分和陪伴分，再结合迟滞策略输出最终会话类型，
         * 避免会话类型在边界场景频繁抖动。
         */
        SessionScore score = score(userInput, eventType, payloadJson, taskState, relationalState);
        SessionType candidate = decide(score.taskScore(), score.companionScore());
        return withHysteresis(candidate, previousType, score.taskScore(), score.companionScore());
    }

    private SessionScore score(String userInput,
                               String eventType,
                               String payloadJson,
                               TaskRuntimeState taskState,
                               RelationalRuntimeState relationalState) {
        /**
         * 综合事件类型、任务态、关系态以及输入关键词对任务属性和陪伴属性打分，
         * 为会话类型判定提供量化依据。
         */
        String text = lower(userInput);
        String type = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        String payload = lower(payloadJson);
        int taskScore = 0;
        int companionScore = 0;

        if ("TOOL_RESULT".equals(type)) {
            taskScore += 2;
        } else if ("APPROVAL".equals(type)) {
            taskScore += 1;
        }

        if (taskState != null) {
            switch (taskState) {
                case PLANNING, REPLANNING, EXECUTING, WAITING_TOOL, WAITING_APPROVAL, WAITING_PLAN_CONFIRMATION -> taskScore += 2;
                case CONTEXT_BUILDING, UNDERSTANDING, REPORTING, REFLECTING -> taskScore += 1;
                default -> {
                }
            }
        }

        if (relationalState != null) {
            switch (relationalState) {
                case EMOTIONAL_SUPPORT, FRAGILE_MOMENT, REPAIRING, DEEP_TALK, COMPANION_MODE -> companionScore += 2;
                case CELEBRATING, TRUST_BUILDING, FAMILIARIZING -> companionScore += 1;
                default -> {
                }
            }
        }

        if (containsAny(text, "implement", "fix", "code", "deliver", "run", "plan", "execute", "修复", "实现", "开发", "执行", "计划", "交付")) {
            taskScore += 2;
        }
        if (containsAny(text, "anxious", "tired", "burnout", "sad", "comfort", "listen", "support", "焦虑", "难受", "崩溃", "先听", "安慰", "陪我")) {
            companionScore += 2;
        }
        if (containsAny(payload, "\"status\":\"failed\"", "\"error\"", "\"failed\"")) {
            taskScore += 1;
            companionScore += 1;
        }
        if (containsAny(payload, "\"emotion\"", "\"boundary\"", "\"support\"", "\"comfort\"")) {
            companionScore += 1;
        }
        return new SessionScore(taskScore, companionScore);
    }

    private SessionType decide(int taskScore, int companionScore) {
        int gap = Math.abs(taskScore - companionScore);
        if (gap <= 1) {
            return SessionType.HYBRID;
        }
        return taskScore > companionScore ? SessionType.TASK : SessionType.COMPANION;
    }

    private SessionType withHysteresis(SessionType candidate, SessionType previousType, int taskScore, int companionScore) {
        SessionType previous = previousType == null ? SessionType.HYBRID : previousType;
        if (candidate == previous) {
            return previous;
        }
        int gap = Math.abs(taskScore - companionScore);
        if (previous == SessionType.HYBRID) {
            return gap >= 2 ? candidate : SessionType.HYBRID;
        }
        if (candidate == SessionType.HYBRID) {
            return gap == 0 ? SessionType.HYBRID : previous;
        }
        return gap >= 2 ? candidate : previous;
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private record SessionScore(int taskScore, int companionScore) {
    }
}

