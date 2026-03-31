package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DefaultSessionOrchestratorService implements SessionOrchestratorService {

    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ContextCompilerService contextCompilerService;

    @Override
    public OrchestrationDecision onUserInput(String sessionId, String userInput) {
        return orchestrate(sessionId, "USER_INPUT", userInput, payloadOf("text", userInput));
    }

    @Override
    public OrchestrationDecision onToolResult(String sessionId, String payloadJson) {
        return orchestrate(sessionId, "TOOL_RESULT", summarizePayload(payloadJson), payloadJson);
    }

    @Override
    public OrchestrationDecision onApproval(String sessionId, String payloadJson) {
        return orchestrate(sessionId, "APPROVAL", summarizePayload(payloadJson), payloadJson);
    }

    @Override
    public OrchestrationDecision onSystemEvent(String sessionId, String eventType, String payloadJson) {
        String trigger = eventType == null || eventType.isBlank() ? "SYSTEM" : eventType;
        return orchestrate(sessionId, trigger, summarizePayload(payloadJson), payloadJson);
    }

    private OrchestrationDecision orchestrate(String sessionId, String eventType, String signal, String payloadJson) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        Long principalId = principalIdOf(normalizedSessionId);
        ensureDefaultAgentIdentity();
        Long agentId = defaultAgentId();

        TaskRuntimeState previousTaskState = getCurrentTaskState(normalizedSessionId);
        RelationalRuntimeState previousRelationalState = getCurrentRelationalState(normalizedSessionId);
        TaskRuntimeState nextTaskState = inferTaskState(previousTaskState, eventType, signal, payloadJson);
        RelationalRuntimeState nextRelationalState = inferRelationalState(previousRelationalState, eventType, signal, payloadJson);

        upsertPrincipal(principalId, normalizedSessionId);
        upsertSession(normalizedSessionId, principalId, agentId, nextTaskState, nextRelationalState, signal);

        String triggerType = safeUpper(eventType);
        String safePayloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        if (previousTaskState != nextTaskState) {
            insertTransition(normalizedSessionId, "TASK", previousTaskState.name(), nextTaskState.name(), triggerType, normalizedSessionId, safePayloadJson);
        }
        if (previousRelationalState != nextRelationalState) {
            insertTransition(normalizedSessionId, "RELATION", previousRelationalState.name(), nextRelationalState.name(), triggerType, normalizedSessionId, safePayloadJson);
        }

        StructuredContextPackage contextPackage = contextCompilerService.compile(
                normalizedSessionId,
                signal,
                nextTaskState,
                nextRelationalState
        );
        return OrchestrationDecision.builder()
                .sessionId(normalizedSessionId)
                .taskState(nextTaskState)
                .relationalState(nextRelationalState)
                .contextPackage(contextPackage)
                .build();
    }

    private TaskRuntimeState getCurrentTaskState(String sessionId) {
        return parseState(sessionRuntimeMapper.selectTaskState(sessionId), TaskRuntimeState.IDLE);
    }

    private RelationalRuntimeState getCurrentRelationalState(String sessionId) {
        return parseState(sessionRuntimeMapper.selectRelationalState(sessionId), RelationalRuntimeState.COLD_START);
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T>> T parseState(String stateText, T fallback) {
        try {
            if (stateText == null || stateText.isBlank()) {
                return fallback;
            }
            return (T) Enum.valueOf(fallback.getDeclaringClass(), stateText.toUpperCase(Locale.ROOT));
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private TaskRuntimeState inferTaskState(TaskRuntimeState previous, String eventType, String signal, String payloadJson) {
        String type = safeUpper(eventType);
        String text = safeLower(signal);
        String payload = safeLower(payloadJson);

        if ("TOOL_RESULT".equals(type)) {
            if (containsAny(payload, "\"status\":\"pending\"", "\"pending\"", "\"waiting\"")) {
                return TaskRuntimeState.WAITING_TOOL;
            }
            if (containsAny(payload, "\"status\":\"failed\"", "\"error\"")) {
                return TaskRuntimeState.REFLECTING;
            }
            if (previous == TaskRuntimeState.WAITING_APPROVAL || previous == TaskRuntimeState.WAITING_TOOL || previous == TaskRuntimeState.EXECUTING) {
                return TaskRuntimeState.EXECUTING;
            }
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if ("APPROVAL".equals(type)) {
            if (containsAny(payload, "\"approved\":true", "\"approved\":1", "\"approved\":\"true\"")) {
                return TaskRuntimeState.EXECUTING;
            }
            if (containsAny(payload, "\"approved\":false", "\"approved\":0", "\"approved\":\"false\"")) {
                return TaskRuntimeState.CANCELLED;
            }
            return TaskRuntimeState.WAITING_APPROVAL;
        }
        if ("SYSTEM".equals(type) || "TIMER".equals(type)) {
            if (previous == TaskRuntimeState.IDLE || previous == TaskRuntimeState.COMPLETED || previous == TaskRuntimeState.CANCELLED) {
                return previous;
            }
            return TaskRuntimeState.REPORTING;
        }

        if (containsAny(text, "cancel", "取消", "终止", "stop")) {
            return TaskRuntimeState.CANCELLED;
        }
        if (containsAny(text, "done", "完成", "已完成", "搞定")) {
            return TaskRuntimeState.COMPLETED;
        }
        if (containsAny(text, "审批", "approve", "approval", "确认")) {
            return TaskRuntimeState.WAITING_APPROVAL;
        }
        if (containsAny(text, "等待", "pending", "tool result", "回调")) {
            return TaskRuntimeState.WAITING_TOOL;
        }
        if (containsAny(text, "计划", "规划", "plan", "roadmap", "方案")) {
            return TaskRuntimeState.PLANNING;
        }
        if (containsAny(text, "执行", "实现", "修复", "run", "build", "写代码")) {
            return TaskRuntimeState.EXECUTING;
        }
        if (containsAny(text, "总结", "汇报", "报告", "report", "复盘")) {
            return TaskRuntimeState.REPORTING;
        }
        if (containsAny(text, "失败", "报错", "重试", "replan", "反思")) {
            return previous == TaskRuntimeState.REFLECTING ? TaskRuntimeState.REPLANNING : TaskRuntimeState.REFLECTING;
        }
        if (containsAny(text, "补充", "上下文", "背景", "context")) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (containsAny(text, "等一下", "暂停", "wait", "later")) {
            return TaskRuntimeState.WAITING_USER;
        }
        if (previous == TaskRuntimeState.WAITING_USER || previous == TaskRuntimeState.WAITING_TOOL || previous == TaskRuntimeState.WAITING_APPROVAL) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (previous == TaskRuntimeState.IDLE || previous == TaskRuntimeState.COMPLETED || previous == TaskRuntimeState.CANCELLED) {
            return TaskRuntimeState.UNDERSTANDING;
        }
        return previous == null ? TaskRuntimeState.UNDERSTANDING : previous;
    }

    private RelationalRuntimeState inferRelationalState(RelationalRuntimeState previous, String eventType, String signal, String payloadJson) {
        String type = safeUpper(eventType);
        String text = safeLower(signal);
        String payload = safeLower(payloadJson);

        if ("APPROVAL".equals(type)) {
            if (containsAny(payload, "\"approved\":false", "\"approved\":0")) {
                return RelationalRuntimeState.REPAIRING;
            }
            return previous == null ? RelationalRuntimeState.TRUST_BUILDING : previous;
        }
        if ("TOOL_RESULT".equals(type) && containsAny(payload, "\"error\"", "\"failed\"")) {
            return RelationalRuntimeState.REPAIRING;
        }
        if (containsAny(text, "撑不住", "焦虑", "崩溃", "很累", "难受", "烦", "低落")) {
            return RelationalRuntimeState.EMOTIONAL_SUPPORT;
        }
        if (containsAny(text, "没懂我", "误会", "冒犯", "不舒服")) {
            return RelationalRuntimeState.REPAIRING;
        }
        if (containsAny(text, "庆祝", "开心", "太好了", "成功了")) {
            return RelationalRuntimeState.CELEBRATING;
        }
        if (containsAny(text, "聊聊心里话", "倾诉", "深聊", "deep talk")) {
            return RelationalRuntimeState.DEEP_TALK;
        }
        if (previous == RelationalRuntimeState.COLD_START) {
            return RelationalRuntimeState.FAMILIARIZING;
        }
        if (previous == RelationalRuntimeState.FAMILIARIZING) {
            return RelationalRuntimeState.TRUST_BUILDING;
        }
        if (previous == RelationalRuntimeState.TRUST_BUILDING && containsAny(text, "一起", "陪我", "长期")) {
            return RelationalRuntimeState.COMPANION_MODE;
        }
        return RelationalRuntimeState.LIGHT_CHAT;
    }

    private void upsertSession(String sessionId,
                               Long principalId,
                               Long agentId,
                               TaskRuntimeState taskState,
                               RelationalRuntimeState relationalState,
                               String goal) {
        try {
            sessionRuntimeMapper.upsertSession(sessionId, principalId, agentId, taskState.name(), relationalState.name(), goal);
        } catch (Exception ignore) {
        }
    }

    private void upsertPrincipal(Long principalId, String sessionId) {
        if (principalId == null) {
            return;
        }
        try {
            sessionRuntimeMapper.touchPrincipal(principalId, sessionId);
        } catch (Exception ignore) {
        }
    }

    private Long principalIdOf(String sessionId) {
        String principalKey = AuthContextHolder.getPrincipalKey();
        if (principalKey != null && !principalKey.isBlank()) {
            try {
                Long resolved = sessionRuntimeMapper.resolvePrincipalIdByKey(principalKey, principalKey);
                if (resolved != null) {
                    return resolved;
                }
            } catch (Exception ignore) {
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionRuntimeMapper.selectPrincipalIdBySession(sessionId);
        } catch (Exception ignore) {
            return null;
        }
    }

    private void insertTransition(String sessionId, String domain, String fromState, String toState, String triggerType, String triggerRef, String payloadJson) {
        try {
            sessionRuntimeMapper.insertTransition(sessionId, domain, fromState, toState, triggerType, triggerRef, payloadJson);
        } catch (Exception ignore) {
        }
    }

    private void ensureDefaultAgentIdentity() {
        try {
            sessionRuntimeMapper.ensureDefaultAgentIdentity();
        } catch (Exception ignore) {
        }
    }

    private Long defaultAgentId() {
        try {
            return sessionRuntimeMapper.selectDefaultAgentId();
        } catch (Exception ignore) {
            return null;
        }
    }

    private String summarizePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        return payloadJson.length() <= 300 ? payloadJson : payloadJson.substring(0, 300);
    }

    private String safeUpper(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    private String safeLower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String payloadOf(String key, String value) {
        String safeKey = key == null || key.isBlank() ? "text" : key.trim();
        String safeValue = value == null ? "" : value;
        return "{\"" + safeKey + "\":\"" + escapeJson(safeValue) + "\"}";
    }

    private String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
