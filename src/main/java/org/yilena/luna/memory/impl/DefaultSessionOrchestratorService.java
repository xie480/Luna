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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DefaultSessionOrchestratorService implements SessionOrchestratorService {

    private static final Pattern PLAN_ID_PATTERN = Pattern.compile("\"(?:plan_id|planId|current_plan_id|currentPlanId)\"\\s*:\\s*(\\d+)");

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

        Long inferredPlanId = inferPlanId(payloadJson, signal);
        if (inferredPlanId != null) {
            updateCurrentPlanId(normalizedSessionId, inferredPlanId);
        }

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
            if (containsAny(payload,
                    "awaiting_plan_confirmation",
                    "waiting_plan_confirmation",
                    "\"plan_status\":\"awaiting_confirmation\"",
                    "\"need_plan_confirmation\":true")) {
                return TaskRuntimeState.WAITING_PLAN_CONFIRMATION;
            }
            if (containsAny(payload, "\"status\":\"failed\"", "\"error\"", "\"failed\"")) {
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

        if (previous == TaskRuntimeState.WAITING_PLAN_CONFIRMATION) {
            if (containsAny(text,
                    "confirm",
                    "approved",
                    "yes",
                    "go ahead",
                    "execute plan",
                    "确认",
                    "同意",
                    "按这个计划",
                    "开始执行")) {
                return TaskRuntimeState.EXECUTING;
            }
            if (containsAny(text,
                    "reject",
                    "modify plan",
                    "change plan",
                    "replan",
                    "不同意",
                    "重做计划",
                    "改方案",
                    "调整计划")) {
                return TaskRuntimeState.REPLANNING;
            }
            return TaskRuntimeState.WAITING_PLAN_CONFIRMATION;
        }

        if (containsAny(text, "cancel", "stop", "abort", "取消", "终止", "停止")) {
            return TaskRuntimeState.CANCELLED;
        }
        if (containsAny(text, "done", "completed", "finish", "完成", "搞定", "结束")) {
            return TaskRuntimeState.COMPLETED;
        }
        if (containsAny(text, "approval", "approve", "need approval", "审批", "批准", "确认权限")) {
            return TaskRuntimeState.WAITING_APPROVAL;
        }
        if (containsAny(text, "pending", "wait tool", "tool result", "callback", "等待工具", "回调", "待返回")) {
            return TaskRuntimeState.WAITING_TOOL;
        }
        if (containsAny(text,
                "confirm plan",
                "approve plan",
                "confirm the plan",
                "确认计划",
                "确认方案",
                "计划确认")) {
            return TaskRuntimeState.WAITING_PLAN_CONFIRMATION;
        }
        if (containsAny(text, "plan", "roadmap", "strategy", "规划", "计划", "方案")) {
            return TaskRuntimeState.PLANNING;
        }
        if (containsAny(text, "execute", "implement", "fix", "run", "build", "coding", "执行", "实现", "修复", "开发")) {
            return TaskRuntimeState.EXECUTING;
        }
        if (containsAny(text, "report", "summary", "retrospective", "汇报", "总结", "报告", "复盘")) {
            return TaskRuntimeState.REPORTING;
        }
        if (containsAny(text, "failed", "error", "retry", "replan", "失败", "报错", "重试", "反思")) {
            return previous == TaskRuntimeState.REFLECTING ? TaskRuntimeState.REPLANNING : TaskRuntimeState.REFLECTING;
        }
        if (containsAny(text, "context", "supplement", "background", "补充", "上下文", "背景")) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (containsAny(text, "wait", "later", "hold on", "稍后", "先等等", "暂停")) {
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
        if (containsAny(text, "anxious", "burnout", "tired", "sad", "stress", "焦虑", "崩溃", "很累", "难受", "低落")) {
            return RelationalRuntimeState.EMOTIONAL_SUPPORT;
        }
        if (containsAny(text, "misunderstand", "offended", "uncomfortable", "误会", "冒犯", "不舒服")) {
            return RelationalRuntimeState.REPAIRING;
        }
        if (containsAny(text, "celebrate", "great", "awesome", "庆祝", "太好了", "成功了", "开心")) {
            return RelationalRuntimeState.CELEBRATING;
        }
        if (containsAny(text, "deep talk", "share", "倾诉", "深聊", "聊聊心里话")) {
            return RelationalRuntimeState.DEEP_TALK;
        }
        if (previous == RelationalRuntimeState.COLD_START) {
            return RelationalRuntimeState.FAMILIARIZING;
        }
        if (previous == RelationalRuntimeState.FAMILIARIZING) {
            return RelationalRuntimeState.TRUST_BUILDING;
        }
        if (previous == RelationalRuntimeState.TRUST_BUILDING && containsAny(text, "together", "陪我", "长期", "一直")) {
            return RelationalRuntimeState.COMPANION_MODE;
        }
        return RelationalRuntimeState.LIGHT_CHAT;
    }

    private Long inferPlanId(String payloadJson, String signal) {
        Long fromPayload = extractPlanId(payloadJson);
        if (fromPayload != null) {
            return fromPayload;
        }
        return extractPlanId(signal);
    }

    private Long extractPlanId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = PLAN_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }

    private void updateCurrentPlanId(String sessionId, Long planId) {
        if (planId == null) {
            return;
        }
        try {
            sessionRuntimeMapper.updateCurrentPlanId(sessionId, planId);
        } catch (Exception ignore) {
        }
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
}
