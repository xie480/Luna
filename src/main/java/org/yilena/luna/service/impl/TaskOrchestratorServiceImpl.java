package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.RecoveryContextAgent;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.TaskOrchestrationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskOrchestratorServiceImpl implements TaskOrchestratorService {

    private final ContextCompilerService contextCompilerService;
    private final InputReconstructionAgent inputReconstructionAgent;
    private final EventIngressService eventIngressService;
    private final RecoveryContextAgent recoveryContextAgent;
    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public TaskOrchestrationResult orchestrateUserInput(String sessionId, String userInput) {
        StructuredContextPackage preContextPackage = contextCompilerService.compile(sessionId, userInput, null, null);
        InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                sessionId,
                userInput,
                preContextPackage,
                preContextPackage == null ? null : preContextPackage.getTaskState(),
                preContextPackage == null ? null : preContextPackage.getRelationalState()
        );
        OrchestrationDecision decision = eventIngressService.ingestUserInput(
                sessionId,
                userInput,
                buildOrchestrationSignal(userInput, reconstructionResult)
        );
        StructuredContextPackage contextPackage = decision == null ? preContextPackage : decision.getContextPackage();
        RecoveryTrigger recoveryTrigger = resolveRecoveryTrigger(userInput, decision, contextPackage);
        if (recoveryTrigger.shouldRecover) {
            contextPackage = recoveryContextAgent.recover(
                    sessionId,
                    contextPackage,
                    recoveryTrigger.recoveryEvent,
                    recoveryTrigger.interruptReason
            );
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_TRIGGERED",
                    "recovery branch entered for interrupted flow",
                    toJsonSafe(Map.of(
                            "event", recoveryTrigger.recoveryEvent,
                            "reason", recoveryTrigger.interruptReason
                    ))
            );
        } else {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_SKIPPED",
                    "normal chat turn without interrupt/resume event",
                    toJsonSafe(Map.of("input", userInput == null ? "" : userInput))
            );
        }
        runtimeAuditService.persistContextSnapshot(sessionId, contextPackage);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected by reconstructed input signal",
                toJsonSafe(buildDecisionStatePayload(decision))
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "INPUT_RECONSTRUCTION",
                "input reconstructed before RAG/MCP routing",
                toJsonSafe(reconstructionResult)
        );
        return TaskOrchestrationResult.builder()
                .decision(decision)
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .recovered(recoveryTrigger.shouldRecover)
                .recoveryEvent(recoveryTrigger.recoveryEvent)
                .interruptReason(recoveryTrigger.interruptReason)
                .build();
    }

    @Override
    public TaskOrchestrationResult orchestrateSystemRecovery(String sessionId,
                                                             String userInput,
                                                             String eventType,
                                                             Map<String, Object> eventPayload,
                                                             String recoveryEvent,
                                                             String interruptReason) {
        OrchestrationDecision decision = eventIngressService.ingestSystemEvent(
                sessionId,
                eventType == null || eventType.isBlank() ? "SYSTEM" : eventType,
                eventPayload == null ? Map.of() : eventPayload
        );
        StructuredContextPackage contextPackage = decision == null ? null : decision.getContextPackage();
        String effectiveRecoveryEvent = recoveryEvent == null || recoveryEvent.isBlank()
                ? "SYSTEM_RECOVERY"
                : recoveryEvent;
        String effectiveInterruptReason = interruptReason == null || interruptReason.isBlank()
                ? "SYSTEM_EVENT"
                : interruptReason;
        contextPackage = recoveryContextAgent.recover(
                sessionId,
                contextPackage,
                effectiveRecoveryEvent,
                effectiveInterruptReason
        );
        InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                sessionId,
                userInput,
                contextPackage,
                decision == null ? null : decision.getTaskState(),
                decision == null ? null : decision.getRelationalState()
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "RECOVERY_TRIGGERED",
                "recovery branch entered from system event",
                toJsonSafe(Map.of(
                        "event", effectiveRecoveryEvent,
                        "reason", effectiveInterruptReason,
                        "eventType", eventType == null ? "SYSTEM" : eventType
                ))
        );
        runtimeAuditService.persistContextSnapshot(sessionId, contextPackage);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected by system recovery event",
                toJsonSafe(buildDecisionStatePayload(decision))
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "INPUT_RECONSTRUCTION",
                "input reconstructed in recovery branch",
                toJsonSafe(reconstructionResult)
        );
        return TaskOrchestrationResult.builder()
                .decision(decision)
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .recovered(true)
                .recoveryEvent(effectiveRecoveryEvent)
                .interruptReason(effectiveInterruptReason)
                .build();
    }

    private String buildOrchestrationSignal(String rawInput, InputReconstructionResult reconstruction) {
        if (reconstruction == null) {
            return rawInput == null ? "" : rawInput;
        }
        StringBuilder signal = new StringBuilder();
        signal.append("intent=").append(nullSafe(reconstruction.getNormalizedUserIntent()));
        signal.append(";goal=").append(nullSafe(reconstruction.getExplicitTaskGoal()));
        signal.append(";timeScope=").append(nullSafe(reconstruction.getTimeScope()));
        signal.append(";constraints=").append(reconstruction.getBusinessConstraints() == null ? List.of() : reconstruction.getBusinessConstraints());
        signal.append(";missingSlots=").append(reconstruction.getMissingSlots() == null ? List.of() : reconstruction.getMissingSlots());
        return signal.toString();
    }

    private RecoveryTrigger resolveRecoveryTrigger(String input,
                                                   OrchestrationDecision decision,
                                                   StructuredContextPackage contextPackage) {
        String normalizedInput = nullSafe(input).trim().toLowerCase(Locale.ROOT);
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        boolean waitingResumeState = taskState == TaskRuntimeState.WAITING_APPROVAL
                || taskState == TaskRuntimeState.WAITING_TOOL
                || taskState == TaskRuntimeState.WAITING_USER;
        boolean explicitResume = containsAny(normalizedInput,
                "resume", "continue", "批准", "通过", "恢复", "继续", "确认", "approve", "confirmed");
        boolean explicitRetry = containsAny(normalizedInput, "retry", "重试", "再试", "重新执行");
        boolean explicitInterruptEvent = containsAny(normalizedInput, "callback", "tool result", "审批结果", "approval result");
        if (waitingResumeState && explicitResume) {
            return new RecoveryTrigger(true, "RESUME_REQUEST", "USER_RESUME_SIGNAL");
        }
        if (waitingResumeState && explicitRetry) {
            return new RecoveryTrigger(true, "RESUME_REQUEST", "USER_RETRY_SIGNAL");
        }
        if (explicitInterruptEvent) {
            return new RecoveryTrigger(true, "EXTERNAL_EVENT", "EVENT_CALLBACK_SIGNAL");
        }
        if (contextPackage != null && contextPackage.getRecoveryState() != null) {
            String previousEvent = nullSafe(contextPackage.getRecoveryState().getRecoveryEvent());
            String previousReason = nullSafe(contextPackage.getRecoveryState().getInterruptReason());
            if (!previousEvent.isBlank() && containsAny(previousReason.toLowerCase(Locale.ROOT),
                    "approval", "tool", "interrupt", "timeout", "failed")) {
                return new RecoveryTrigger(true, previousEvent, previousReason);
            }
        }
        return new RecoveryTrigger(false, "", "");
    }

    private Map<String, Object> buildDecisionStatePayload(OrchestrationDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskState", decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name());
        payload.put("relationalState", decision == null || decision.getRelationalState() == null ? "" : decision.getRelationalState().name());
        return payload;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return null;
        }
        Object session = contextPackage.getRuntime().get("session");
        if (session instanceof Map<?, ?> row) {
            return toLong(row.get("current_plan_id"));
        }
        return null;
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return null;
        }
        Object working = contextPackage.getTaskContext().get("working_memory");
        if (working instanceof Map<?, ?> row) {
            return toLong(row.get("active_node_id"));
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record RecoveryTrigger(boolean shouldRecover, String recoveryEvent, String interruptReason) {
    }
}
