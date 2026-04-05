package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.SessionType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.SessionOrchestratorService;
import org.yilena.luna.memory.model.GovernedSignal;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DefaultSessionOrchestratorService implements SessionOrchestratorService {

    private static final Pattern PLAN_ID_PATTERN = Pattern.compile("\"(?:plan_id|planId|current_plan_id|currentPlanId)\"\\s*:\\s*(\\d+)");

    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ContextCompilerService contextCompilerService;
    private final SessionTypeResolver sessionTypeResolver;
    private final ObjectMapper objectMapper;

    @Value("${memory.session-type.enabled:true}")
    private boolean sessionTypeEnabled;

    @Value("${strict_governed_signal_mode:${memory.strict_governed_signal_mode:true}}")
    private boolean strictGovernedSignalMode;

    @Override
    public OrchestrationDecision onUserInput(String sessionId, String userInput) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        GovernedSignal governedSignal = GovernedSignal.fromRawInput(userInput);
        String signal = toSignalPayload(governedSignal);
        return orchestrate(normalizedSessionId, "USER_INPUT", signal, buildUserInputPayload(userInput, governedSignal));
    }

    @Override
    public OrchestrationDecision onUserInput(String sessionId, String userInput, String orchestrationSignal) {
        String normalizedSessionId = sessionId == null || sessionId.isBlank() ? "default-session" : sessionId;
        GovernedSignal governedSignal = parseGovernedSignal(orchestrationSignal, userInput);
        String signal = toSignalPayload(governedSignal);
        return orchestrate(normalizedSessionId, "USER_INPUT", signal, buildUserInputPayload(userInput, governedSignal));
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
        SessionType previousSessionType = getCurrentSessionType(normalizedSessionId);
        ExecutionSnapshot executionSnapshot = resolveExecutionSnapshot(normalizedSessionId);

        TaskRuntimeState nextTaskState = inferTaskState(previousTaskState, eventType, signal, payloadJson, executionSnapshot);
        RelationalRuntimeState nextRelationalState = inferRelationalState(previousRelationalState, eventType, signal, payloadJson);
        SessionType nextSessionType = resolveSessionType(
                signal,
                eventType,
                payloadJson,
                nextTaskState,
                nextRelationalState,
                previousSessionType
        );

        Long inferredPlanId = inferPlanId(payloadJson, signal);
        if (inferredPlanId != null) {
            updateCurrentPlanId(normalizedSessionId, inferredPlanId);
        }

        upsertPrincipal(principalId, normalizedSessionId);
        upsertSession(normalizedSessionId, principalId, agentId, nextSessionType, nextTaskState, nextRelationalState, signal);

        String triggerType = safeUpper(eventType);
        String safePayloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        if (previousTaskState != nextTaskState) {
            insertTransition(normalizedSessionId, "TASK", previousTaskState.name(), nextTaskState.name(), triggerType, normalizedSessionId, safePayloadJson);
        }
        if (previousRelationalState != nextRelationalState) {
            insertTransition(normalizedSessionId, "RELATION", previousRelationalState.name(), nextRelationalState.name(), triggerType, normalizedSessionId, safePayloadJson);
        }
        if (previousSessionType != nextSessionType) {
            insertTransition(normalizedSessionId, "SESSION_TYPE", previousSessionType.name(), nextSessionType.name(), triggerType, normalizedSessionId, safePayloadJson);
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

    private SessionType getCurrentSessionType(String sessionId) {
        return SessionType.from(sessionRuntimeMapper.selectSessionType(sessionId));
    }

    private SessionType resolveSessionType(String signal,
                                           String eventType,
                                           String payloadJson,
                                           TaskRuntimeState taskState,
                                           RelationalRuntimeState relationalState,
                                           SessionType previousSessionType) {
        if (!sessionTypeEnabled) {
            return SessionType.HYBRID;
        }
        try {
            return sessionTypeResolver.resolve(signal, eventType, payloadJson, taskState, relationalState, previousSessionType);
        } catch (Exception ignore) {
            return previousSessionType == null ? SessionType.HYBRID : previousSessionType;
        }
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

    private TaskRuntimeState inferTaskState(TaskRuntimeState previous,
                                            String eventType,
                                            String signal,
                                            String payloadJson,
                                            ExecutionSnapshot executionSnapshot) {
        String type = safeUpper(eventType);
        String payload = safeLower(payloadJson);
        StructuredSignal structuredSignal = parseStructuredSignal(signal);

        TaskRuntimeState runtimeDriven = inferTaskStateFromExecution(executionSnapshot, previous);

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
                return runtimeDriven == TaskRuntimeState.FAILED ? TaskRuntimeState.FAILED : TaskRuntimeState.REFLECTING;
            }
            if (runtimeDriven != null) {
                return runtimeDriven;
            }
            if (previous == TaskRuntimeState.WAITING_APPROVAL || previous == TaskRuntimeState.WAITING_TOOL || previous == TaskRuntimeState.EXECUTING) {
                return TaskRuntimeState.EXECUTING;
            }
            return TaskRuntimeState.CONTEXT_BUILDING;
        }

        if ("APPROVAL".equals(type)) {
            if (containsAny(payload, "\"approved\":true", "\"approved\":1", "\"approved\":\"true\"")) {
                return runtimeDriven == null ? TaskRuntimeState.EXECUTING : runtimeDriven;
            }
            if (containsAny(payload, "\"approved\":false", "\"approved\":0", "\"approved\":\"false\"")) {
                return TaskRuntimeState.CANCELLED;
            }
            return runtimeDriven == null ? TaskRuntimeState.WAITING_APPROVAL : runtimeDriven;
        }

        if ("SYSTEM".equals(type) || "TIMER".equals(type)) {
            if (runtimeDriven != null) {
                return runtimeDriven;
            }
            if (previous == TaskRuntimeState.IDLE || previous == TaskRuntimeState.COMPLETED || previous == TaskRuntimeState.CANCELLED) {
                return previous;
            }
            return TaskRuntimeState.REPORTING;
        }

        if (containsSignal(structuredSignal, "cancel", "stop", "abort", "取消", "终止", "停止")) {
            return TaskRuntimeState.CANCELLED;
        }

        if (runtimeDriven != null) {
            if (runtimeDriven == TaskRuntimeState.WAITING_PLAN_CONFIRMATION) {
                if (isPlanConfirmationSignal(structuredSignal)) {
                    return TaskRuntimeState.EXECUTING;
                }
                if (isPlanRejectionSignal(structuredSignal)) {
                    return TaskRuntimeState.REPLANNING;
                }
            }
            return runtimeDriven;
        }

        if (previous == TaskRuntimeState.WAITING_PLAN_CONFIRMATION) {
            if (isPlanConfirmationSignal(structuredSignal)) {
                return TaskRuntimeState.EXECUTING;
            }
            if (isPlanRejectionSignal(structuredSignal)) {
                return TaskRuntimeState.REPLANNING;
            }
            return TaskRuntimeState.WAITING_PLAN_CONFIRMATION;
        }

        TaskRuntimeState structuredDriven = inferTaskStateFromStructuredSignal(previous, structuredSignal);
        if (structuredDriven != null) {
            return structuredDriven;
        }

        if (structuredSignal.structured()) {
            if (previous == TaskRuntimeState.WAITING_USER
                    || previous == TaskRuntimeState.WAITING_TOOL
                    || previous == TaskRuntimeState.WAITING_APPROVAL) {
                return TaskRuntimeState.CONTEXT_BUILDING;
            }
            if (previous == TaskRuntimeState.IDLE || previous == TaskRuntimeState.COMPLETED || previous == TaskRuntimeState.CANCELLED) {
                return TaskRuntimeState.UNDERSTANDING;
            }
            return previous == null ? TaskRuntimeState.UNDERSTANDING : previous;
        }

        if (strictGovernedSignalMode) {
            return fallbackFromPreviousTaskState(previous);
        }

        String keywordSignal = structuredSignal.taskCorpus();

        if (containsAny(keywordSignal, "done", "completed", "finish", "完成", "搞定", "结束")) {
            return TaskRuntimeState.COMPLETED;
        }
        if (containsAny(keywordSignal, "approval", "approve", "need approval", "审批", "批准", "确认权限")) {
            return TaskRuntimeState.WAITING_APPROVAL;
        }
        if (containsAny(keywordSignal, "pending", "wait tool", "tool result", "callback", "等待工具", "回调", "待返回")) {
            return TaskRuntimeState.WAITING_TOOL;
        }
        if (containsAny(keywordSignal, "confirm plan", "approve plan", "confirm the plan", "确认计划", "确认方案", "计划确认")) {
            return TaskRuntimeState.WAITING_PLAN_CONFIRMATION;
        }
        if (containsAny(keywordSignal, "plan", "roadmap", "strategy", "规划", "计划", "方案")) {
            return TaskRuntimeState.PLANNING;
        }
        if (containsAny(keywordSignal, "execute", "implement", "fix", "run", "build", "coding", "执行", "实现", "修复", "开发")) {
            return TaskRuntimeState.EXECUTING;
        }
        if (containsAny(keywordSignal, "report", "summary", "retrospective", "汇报", "总结", "报告", "复盘")) {
            return TaskRuntimeState.REPORTING;
        }
        if (containsAny(keywordSignal, "failed", "error", "retry", "replan", "失败", "报错", "重试", "反思")) {
            return previous == TaskRuntimeState.REFLECTING ? TaskRuntimeState.REPLANNING : TaskRuntimeState.REFLECTING;
        }
        if (containsAny(keywordSignal, "context", "supplement", "background", "补充", "上下文", "背景")) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (containsAny(keywordSignal, "wait", "later", "hold on", "稍后", "先等等", "暂停")) {
            return TaskRuntimeState.WAITING_USER;
        }
        return fallbackFromPreviousTaskState(previous);
    }

    private TaskRuntimeState inferTaskStateFromExecution(ExecutionSnapshot snapshot, TaskRuntimeState previous) {
        if (snapshot == null || snapshot.planStatus == null) {
            return null;
        }

        if (snapshot.nodeStatus == NodeExecutionStatus.APPROVAL_PENDING) {
            return TaskRuntimeState.WAITING_APPROVAL;
        }
        if (snapshot.planStatus == PlanExecutionStatus.WAITING_USER_APPROVAL) {
            return TaskRuntimeState.WAITING_PLAN_CONFIRMATION;
        }
        if (snapshot.planStatus == PlanExecutionStatus.RUNNING) {
            if (snapshot.nodeStatus == NodeExecutionStatus.RUNNING) {
                return TaskRuntimeState.EXECUTING;
            }
            if (snapshot.nodeStatus == NodeExecutionStatus.PENDING || snapshot.nodeStatus == NodeExecutionStatus.BLOCKED) {
                return TaskRuntimeState.EXECUTING;
            }
            if (snapshot.nodeStatus == NodeExecutionStatus.FAILED) {
                return TaskRuntimeState.REFLECTING;
            }
            if (snapshot.nodeStatus == NodeExecutionStatus.SUCCESS) {
                return TaskRuntimeState.REPORTING;
            }
            return previous == null ? TaskRuntimeState.EXECUTING : previous;
        }
        if (snapshot.planStatus == PlanExecutionStatus.PENDING) {
            return TaskRuntimeState.PLANNING;
        }
        if (snapshot.planStatus == PlanExecutionStatus.SUCCESS) {
            return TaskRuntimeState.COMPLETED;
        }
        if (snapshot.planStatus == PlanExecutionStatus.FAILED) {
            return TaskRuntimeState.FAILED;
        }
        if (snapshot.planStatus == PlanExecutionStatus.CANCELLED) {
            return TaskRuntimeState.CANCELLED;
        }
        return null;
    }

    private RelationalRuntimeState inferRelationalState(RelationalRuntimeState previous, String eventType, String signal, String payloadJson) {
        String type = safeUpper(eventType);
        String payload = safeLower(payloadJson);
        StructuredSignal structuredSignal = parseStructuredSignal(signal);
        if ("APPROVAL".equals(type)) {
            if (containsAny(payload, "\"approved\":false", "\"approved\":0")) {
                return RelationalRuntimeState.REPAIRING;
            }
            return previous == null ? RelationalRuntimeState.TRUST_BUILDING : previous;
        }
        if ("TOOL_RESULT".equals(type) && containsAny(payload, "\"error\"", "\"failed\"")) {
            return RelationalRuntimeState.REPAIRING;
        }
        if (containsSignal(structuredSignal, "anxious", "burnout", "tired", "sad", "stress", "焦虑", "崩溃", "很累", "难受", "低落")) {
            return RelationalRuntimeState.EMOTIONAL_SUPPORT;
        }
        if (containsSignal(structuredSignal, "misunderstand", "offended", "uncomfortable", "误会", "冒犯", "不舒服")) {
            return RelationalRuntimeState.REPAIRING;
        }
        if (containsSignal(structuredSignal, "celebrate", "great", "awesome", "庆祝", "太好了", "成功了", "开心")) {
            return RelationalRuntimeState.CELEBRATING;
        }
        if (containsSignal(structuredSignal, "deep talk", "share", "倾诉", "深聊", "聊聊心里话")) {
            return RelationalRuntimeState.DEEP_TALK;
        }
        if (previous == RelationalRuntimeState.COLD_START) {
            return RelationalRuntimeState.FAMILIARIZING;
        }
        if (previous == RelationalRuntimeState.FAMILIARIZING) {
            return RelationalRuntimeState.TRUST_BUILDING;
        }
        if (previous == RelationalRuntimeState.TRUST_BUILDING
                && containsSignal(structuredSignal, "together", "陪我", "长期", "一直")) {
            return RelationalRuntimeState.COMPANION_MODE;
        }
        if (structuredSignal.structured() && structuredSignal.hasGoalOrIntent()) {
            return previous == null ? RelationalRuntimeState.FAMILIARIZING : previous;
        }
        return RelationalRuntimeState.LIGHT_CHAT;
    }

    private TaskRuntimeState inferTaskStateFromStructuredSignal(TaskRuntimeState previous, StructuredSignal signal) {
        if (signal == null || !signal.structured()) {
            return null;
        }
        if (signal.reconstructionRetryRequired()) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (signal.reconstructionIncomplete()) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (containsAny(signal.taskCorpus(), "done", "completed", "finish", "完成", "搞定", "结束")) {
            return TaskRuntimeState.COMPLETED;
        }
        if (signal.missingSlotCount() > 0) {
            return TaskRuntimeState.PLANNING;
        }
        if (containsAny(signal.taskCorpus(), "approval", "approve", "need approval", "审批", "批准", "确认权限")) {
            return TaskRuntimeState.WAITING_APPROVAL;
        }
        if (containsAny(signal.taskCorpus(), "wait", "later", "hold on", "稍后", "先等等", "暂停")) {
            return TaskRuntimeState.WAITING_USER;
        }
        if (signal.hasGoalOrIntent()) {
            if (previous == TaskRuntimeState.IDLE || previous == TaskRuntimeState.COMPLETED || previous == TaskRuntimeState.CANCELLED) {
                return TaskRuntimeState.UNDERSTANDING;
            }
            if (previous == TaskRuntimeState.WAITING_USER
                    || previous == TaskRuntimeState.WAITING_TOOL
                    || previous == TaskRuntimeState.WAITING_APPROVAL) {
                return TaskRuntimeState.CONTEXT_BUILDING;
            }
        }
        return null;
    }

    private boolean isPlanConfirmationSignal(StructuredSignal signal) {
        return containsSignal(signal,
                "confirm", "approved", "yes", "go ahead", "execute plan", "确认", "同意", "按这个计划", "开始执行");
    }

    private boolean isPlanRejectionSignal(StructuredSignal signal) {
        return containsSignal(signal,
                "reject", "modify plan", "change plan", "replan", "不同意", "重做计划", "改方案", "调整计划");
    }

    private boolean containsSignal(StructuredSignal signal, String... words) {
        if (signal == null) {
            return false;
        }
        String primary = signal.structured() ? signal.intent() : signal.taskCorpus();
        return containsAny(primary, words);
    }

    private StructuredSignal parseStructuredSignal(String signal) {
        String rawSignal = signal == null ? "" : signal;
        String raw = safeLower(rawSignal);
        GovernedSignal governedSignal = parseGovernedSignal(rawSignal, "");
        if (governedSignal == null) {
            return StructuredSignal.empty(raw);
        }
        String intent = safeLower(governedSignal.getIntent());
        String goal = safeLower(governedSignal.getGoal());
        String timeScope = safeLower(governedSignal.getTimeScope());
        String fallback = safeLower(governedSignal.getFallback());
        return new StructuredSignal(
                true,
                intent,
                goal,
                timeScope,
                fallback,
                governedSignal.safeMissingSlots().size(),
                governedSignal.safeConstraints().size(),
                raw
        );
    }

    private ExecutionSnapshot resolveExecutionSnapshot(String sessionId) {
        try {
            Map<String, Object> planRuntime = sessionRuntimeMapper.selectLatestPlanRuntimeBySession(sessionId);
            if (planRuntime == null || planRuntime.isEmpty()) {
                return null;
            }
            String planId = stringValue(planRuntime.get("plan_id"));
            Map<String, Object> nodeRuntime = planId == null || planId.isBlank()
                    ? Map.of()
                    : sessionRuntimeMapper.selectLatestNodeRuntimeByPlanId(planId);
            return new ExecutionSnapshot(
                    parsePlanExecutionStatus(planRuntime.get("status")),
                    parseNodeExecutionStatus(nodeRuntime.get("status"))
            );
        } catch (Exception ignore) {
            return null;
        }
    }

    private PlanExecutionStatus parsePlanExecutionStatus(Object rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        Integer numeric = toInteger(rawStatus);
        if (numeric != null) {
            return switch (numeric) {
                case 0 -> PlanExecutionStatus.PENDING;
                case 1 -> PlanExecutionStatus.RUNNING;
                case 2 -> PlanExecutionStatus.WAITING_USER_APPROVAL;
                case 3 -> PlanExecutionStatus.SUCCESS;
                case 4 -> PlanExecutionStatus.FAILED;
                case 5 -> PlanExecutionStatus.CANCELLED;
                default -> null;
            };
        }
        String text = safeUpper(String.valueOf(rawStatus));
        if (text.contains("PENDING")) {
            return PlanExecutionStatus.PENDING;
        }
        if (text.contains("RUNNING")) {
            return PlanExecutionStatus.RUNNING;
        }
        if (text.contains("WAITING_USER_APPROVAL") || text.contains("WAITING_APPROVAL")) {
            return PlanExecutionStatus.WAITING_USER_APPROVAL;
        }
        if (text.contains("SUCCESS")) {
            return PlanExecutionStatus.SUCCESS;
        }
        if (text.contains("FAILED")) {
            return PlanExecutionStatus.FAILED;
        }
        if (text.contains("CANCELLED")) {
            return PlanExecutionStatus.CANCELLED;
        }
        return null;
    }

    private NodeExecutionStatus parseNodeExecutionStatus(Object rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        Integer numeric = toInteger(rawStatus);
        if (numeric != null) {
            return switch (numeric) {
                case 0 -> NodeExecutionStatus.PENDING;
                case 1 -> NodeExecutionStatus.RUNNING;
                case 2 -> NodeExecutionStatus.SUCCESS;
                case 3 -> NodeExecutionStatus.FAILED;
                case 4 -> NodeExecutionStatus.BLOCKED;
                case 5 -> NodeExecutionStatus.APPROVAL_PENDING;
                case 6 -> NodeExecutionStatus.SKIPPED;
                default -> null;
            };
        }
        String text = safeUpper(String.valueOf(rawStatus));
        if (text.contains("PENDING") && text.contains("APPROVAL")) {
            return NodeExecutionStatus.APPROVAL_PENDING;
        }
        if (text.contains("PENDING")) {
            return NodeExecutionStatus.PENDING;
        }
        if (text.contains("RUNNING")) {
            return NodeExecutionStatus.RUNNING;
        }
        if (text.contains("SUCCESS")) {
            return NodeExecutionStatus.SUCCESS;
        }
        if (text.contains("FAILED")) {
            return NodeExecutionStatus.FAILED;
        }
        if (text.contains("BLOCKED")) {
            return NodeExecutionStatus.BLOCKED;
        }
        if (text.contains("SKIPPED")) {
            return NodeExecutionStatus.SKIPPED;
        }
        return null;
    }

    private Integer toInteger(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
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
                               SessionType sessionType,
                               TaskRuntimeState taskState,
                               RelationalRuntimeState relationalState,
                               String goal) {
        try {
            sessionRuntimeMapper.upsertSession(
                    sessionId,
                    principalId,
                    agentId,
                    sessionType == null ? SessionType.HYBRID.name() : sessionType.name(),
                    taskState.name(),
                    relationalState.name(),
                    goal
            );
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

    private String buildUserInputPayload(String userInput, GovernedSignal governedSignal) {
        String raw = userInput == null ? "" : userInput;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("raw_input_length", raw.length());
        audit.put("raw_input_sha256", sha256Hex(raw));
        audit.put("strict_governed_signal_mode", strictGovernedSignalMode);
        audit.put("fallback", governedSignal == null ? "reconstruct_retry_required" : normalizeSignalText(governedSignal.getFallback(), "reconstruct_retry_required"));
        audit.put("missing_slots_count", governedSignal == null ? 0 : governedSignal.safeMissingSlots().size());
        audit.put("warning_code", governedSignal == null || governedSignal.safeMissingSlots().isEmpty()
                ? ""
                : "GOVERNED_SIGNAL_INCOMPLETE_FALLBACK_ALERT");
        try {
            return objectMapper.writeValueAsString(audit);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private TaskRuntimeState fallbackFromPreviousTaskState(TaskRuntimeState previous) {
        if (previous == TaskRuntimeState.WAITING_USER
                || previous == TaskRuntimeState.WAITING_TOOL
                || previous == TaskRuntimeState.WAITING_APPROVAL) {
            return TaskRuntimeState.CONTEXT_BUILDING;
        }
        if (previous == TaskRuntimeState.IDLE
                || previous == TaskRuntimeState.COMPLETED
                || previous == TaskRuntimeState.CANCELLED) {
            return TaskRuntimeState.UNDERSTANDING;
        }
        return previous == null ? TaskRuntimeState.UNDERSTANDING : previous;
    }

    private GovernedSignal parseGovernedSignal(String signalPayload, String rawInput) {
        if (signalPayload != null && !signalPayload.isBlank()) {
            GovernedSignal fromJson = tryParseSignalJson(signalPayload);
            if (fromJson != null) {
                return sanitizeSignal(fromJson, rawInput);
            }
            GovernedSignal fromLegacy = tryParseLegacySignal(signalPayload, rawInput);
            if (fromLegacy != null) {
                return sanitizeSignal(fromLegacy, rawInput);
            }
        }
        return GovernedSignal.fromRawInput(rawInput);
    }

    private GovernedSignal tryParseSignalJson(String payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(payload, Map.class);
            return fromSignalMap(map);
        } catch (Exception ignore) {
            return null;
        }
    }

    private GovernedSignal tryParseLegacySignal(String payload, String rawInput) {
        Map<String, String> fields = new LinkedHashMap<>();
        String[] segments = payload.split(";");
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            int separator = segment.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = segment.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = segment.substring(separator + 1).trim();
            if (!key.isBlank()) {
                fields.put(key, value);
            }
        }
        if (fields.isEmpty()) {
            return null;
        }
        List<String> constraints = parseLegacyList(fields.get("constraints"));
        List<String> missingSlots = parseLegacyList(fields.get("missingslots"));
        return GovernedSignal.builder()
                .debugFlag(GovernedSignal.fromRawInput(rawInput).isDebugFlag())
                .intent(normalizeSignalText(fields.get("intent"), "intent_unavailable"))
                .goal(normalizeSignalText(fields.get("goal"), "goal_unavailable"))
                .constraints(constraints)
                .timeScope(normalizeSignalText(fields.get("timescope"), "unspecified"))
                .missingSlots(missingSlots)
                .fallback(normalizeSignalText(fields.get("fallback"), "reconstruct_retry_required"))
                .build();
    }

    private GovernedSignal fromSignalMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        boolean debugFlag = boolValue(map.get("debugFlag"));
        String intent = normalizeSignalText(stringValue(map.get("intent")), "intent_unavailable");
        String goal = normalizeSignalText(stringValue(map.get("goal")), "goal_unavailable");
        String timeScope = normalizeSignalText(stringValue(map.get("timeScope")), "unspecified");
        String fallback = normalizeSignalText(stringValue(map.get("fallback")), "reconstruct_retry_required");
        List<String> constraints = normalizeSignalList(map.get("constraints"));
        List<String> missingSlots = normalizeSignalList(map.get("missingSlots"));
        return GovernedSignal.builder()
                .debugFlag(debugFlag)
                .intent(intent)
                .goal(goal)
                .constraints(constraints)
                .timeScope(timeScope)
                .missingSlots(missingSlots)
                .fallback(fallback)
                .build();
    }

    private GovernedSignal sanitizeSignal(GovernedSignal signal, String rawInput) {
        if (signal == null) {
            return GovernedSignal.fromRawInput(rawInput);
        }
        boolean debugFlag = signal.isDebugFlag() || GovernedSignal.fromRawInput(rawInput).isDebugFlag();
        return GovernedSignal.builder()
                .debugFlag(debugFlag)
                .intent(normalizeSignalText(signal.getIntent(), "intent_unavailable"))
                .goal(normalizeSignalText(signal.getGoal(), "goal_unavailable"))
                .constraints(signal.safeConstraints())
                .timeScope(normalizeSignalText(signal.getTimeScope(), "unspecified"))
                .missingSlots(signal.safeMissingSlots())
                .fallback(normalizeSignalText(signal.getFallback(), "reconstruct_retry_required"))
                .build();
    }

    private String toSignalPayload(GovernedSignal signal) {
        GovernedSignal safeSignal = sanitizeSignal(signal, "");
        try {
            return objectMapper.writeValueAsString(safeSignal);
        } catch (Exception ignore) {
            return "{\"debugFlag\":false,\"intent\":\"intent_unavailable\",\"goal\":\"goal_unavailable\",\"constraints\":[],\"timeScope\":\"unspecified\",\"missingSlots\":[\"reconstruction_missing\"],\"fallback\":\"reconstruct_retry_required\"}";
        }
    }

    private String normalizeSignalText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private List<String> normalizeSignalList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out.stream().distinct().toList();
    }

    private List<String> parseLegacyList(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of();
        }
        String text = rawValue.trim();
        if (text.startsWith("[") && text.endsWith("]") && text.length() > 1) {
            text = text.substring(1, text.length() - 1);
        }
        if (text.isBlank()) {
            return List.of();
        }
        String[] chunks = text.split(",");
        List<String> out = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String item = chunk.trim();
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out.stream().distinct().toList();
    }

    private String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ignore) {
            return "";
        }
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

    private record StructuredSignal(boolean structured,
                                    String intent,
                                    String goal,
                                    String timeScope,
                                    String fallback,
                                    int missingSlotCount,
                                    int constraintCount,
                                    String rawSignal) {
        private static StructuredSignal empty(String rawSignal) {
            return new StructuredSignal(false, "", "", "", "", 0, 0, rawSignal == null ? "" : rawSignal);
        }

        private String taskCorpus() {
            return (intent + " " + goal + " " + timeScope + " " + fallback).trim();
        }

        private boolean hasGoalOrIntent() {
            boolean hasIntent = intent != null && !intent.isBlank() && !intent.contains("intent_unavailable");
            boolean hasGoal = goal != null && !goal.isBlank() && !goal.contains("goal_unavailable");
            return hasIntent || hasGoal;
        }

        private boolean reconstructionIncomplete() {
            if (!structured) {
                return true;
            }
            return (fallback != null && (
                    fallback.contains("reconstruct_retry_required")
                            || fallback.contains("reconstruction_missing")
                            || fallback.contains("reconstruction_partial")
                            || fallback.contains("empty_orchestration_signal")
            )) || !hasGoalOrIntent();
        }

        private boolean reconstructionRetryRequired() {
            if (!structured || fallback == null || fallback.isBlank()) {
                return false;
            }
            return fallback.contains("reconstruct_retry_required");
        }
    }

    private record ExecutionSnapshot(PlanExecutionStatus planStatus, NodeExecutionStatus nodeStatus) {
    }

    private enum PlanExecutionStatus {
        PENDING,
        RUNNING,
        WAITING_USER_APPROVAL,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    private enum NodeExecutionStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        BLOCKED,
        APPROVAL_PENDING,
        SKIPPED
    }
}

