package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.mapper.RuntimeAuditMapper;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.store.ContextSnapshotStore;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class JdbcRuntimeAuditService implements RuntimeAuditService {

    private final RuntimeAuditMapper runtimeAuditMapper;
    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ObjectMapper objectMapper;
    private final ContextSnapshotStore contextSnapshotStore;

    @Override
    public void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage) {
        if (contextPackage == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(contextPackage);
            Long planId = contextPlanId(contextPackage);
            Long nodeId = contextNodeId(contextPackage);
            runtimeAuditMapper.insertContextSnapshot(sessionId, coalescePlanId(sessionId, planId), coalesceNodeId(sessionId, nodeId), payload);
        } catch (Exception ignore) {
        }
    }

    @Override
    public String persistFinalContextSnapshot(String sessionId,
                                              Long planId,
                                              Long nodeId,
                                              AssembledContext assembledContext,
                                              String prompt,
                                              Map<String, Integer> sectionTokenCounts,
                                              Map<String, Double> sectionTokenRatios,
                                              Map<String, Object> rawToolResultChannel,
                                              Map<String, java.util.List<String>> activeRefs) {
        return contextSnapshotStore.saveFinalSnapshot(
                sessionId,
                coalescePlanId(sessionId, planId),
                coalesceNodeId(sessionId, nodeId),
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                rawToolResultChannel,
                activeRefs
        );
    }

    @Override
    public void persistDecisionRecord(String sessionId,
                                      Long planId,
                                      Long nodeId,
                                      String decisionType,
                                      String decisionReason,
                                      String decisionPayloadJson) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String payload = normalizePayload(decisionPayloadJson);
            runtimeAuditMapper.insertDecisionRecord(
                    sessionId,
                    coalescePlanId(sessionId, planId),
                    coalesceNodeId(sessionId, nodeId),
                    decisionType,
                    decisionReason,
                    payload
            );
        } catch (Exception ignore) {
        }
    }

    @Override
    public void persistToolExecutionTrace(String sessionId,
                                          Long planId,
                                          Long nodeId,
                                          String toolName,
                                          String callStatus,
                                          String normalizedInputJson,
                                          String normalizedOutputJson,
                                          String errorMessage,
                                          Long latencyMs) {
        persistToolExecutionTraceAndReturnId(
                sessionId,
                planId,
                nodeId,
                toolName,
                callStatus,
                normalizedInputJson,
                normalizedOutputJson,
                errorMessage,
                latencyMs
        );
    }

    @Override
    public Long persistToolExecutionTraceAndReturnId(String sessionId,
                                                     Long planId,
                                                     Long nodeId,
                                                     String toolName,
                                                     String callStatus,
                                                     String normalizedInputJson,
                                                     String normalizedOutputJson,
                                                     String errorMessage,
                                                     Long latencyMs) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            String safeInput = normalizePayload(normalizedInputJson);
            String safeOutput = normalizePayload(normalizedOutputJson);
            Long insertedId = runtimeAuditMapper.insertToolExecutionTraceAndReturnId(
                    sessionId,
                    coalescePlanId(sessionId, planId),
                    coalesceNodeId(sessionId, nodeId),
                    toolName == null || toolName.isBlank() ? "agent_tool_chain" : toolName,
                    callStatus == null || callStatus.isBlank() ? "UNKNOWN" : callStatus,
                    safeInput,
                    safeOutput,
                    errorMessage,
                    latencyMs
            );
            if (insertedId != null) {
                return insertedId;
            }
            runtimeAuditMapper.insertToolExecutionTrace(
                    sessionId,
                    coalescePlanId(sessionId, planId),
                    coalesceNodeId(sessionId, nodeId),
                    toolName == null || toolName.isBlank() ? "agent_tool_chain" : toolName,
                    callStatus == null || callStatus.isBlank() ? "UNKNOWN" : callStatus,
                    safeInput,
                    safeOutput,
                    errorMessage,
                    latencyMs
            );
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getRuntime() == null) {
                return null;
            }
            Object session = contextPackage.getRuntime().get("session");
            if (session instanceof Map<?, ?> row) {
                return toLong(row.get("current_plan_id"));
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getTaskContext() == null) {
                return null;
            }
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> row) {
                return toLong(row.get("active_node_id"));
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long coalescePlanId(String sessionId, Long explicitPlanId) {
        if (explicitPlanId != null) {
            return explicitPlanId;
        }
        try {
            return sessionRuntimeMapper.selectCurrentPlanIdBySession(sessionId);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long coalesceNodeId(String sessionId, Long explicitNodeId) {
        if (explicitNodeId != null) {
            return explicitNodeId;
        }
        try {
            return sessionRuntimeMapper.selectActiveNodeIdBySession(sessionId);
        } catch (Exception ignore) {
            return null;
        }
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

    private String normalizePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return "{}";
        }
        try {
            objectMapper.readTree(rawPayload);
            return rawPayload;
        } catch (Exception parseIgnore) {
            try {
                return objectMapper.writeValueAsString(java.util.Map.of("raw", rawPayload));
            } catch (Exception writeIgnore) {
                return "{}";
            }
        }
    }
}
