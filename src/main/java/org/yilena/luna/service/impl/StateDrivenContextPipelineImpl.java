package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.service.RoundPipelineOrchestrator;
import org.yilena.luna.service.StateDrivenContextPipeline;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class StateDrivenContextPipelineImpl implements StateDrivenContextPipeline {

    private final RoundPipelineOrchestrator roundPipelineOrchestrator;
    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public RoundPipelineResult run(StateDrivenContextPipelineRequest request) {
        if (request == null || request.getRoundPipelineRequest() == null) {
            return RoundPipelineResult.builder()
                    .blocked(true)
                    .blockedReason("state_driven_context_pipeline_request_missing")
                    .build();
        }
        RoundPipelineRequest roundRequest = request.getRoundPipelineRequest();
        String sessionId = firstNonBlank(request.getSessionId(), roundRequest.getSessionId());
        String triggerSource = firstNonBlank(request.getTriggerSource(), "STATE_DRIVEN_CONTEXT_PIPELINE");
        Long planId = contextPlanId(roundRequest);
        Long nodeId = contextNodeId(roundRequest);
        auditHook(sessionId, planId, nodeId, "reconstruct", triggerSource, Map.of(
                "hasReconstruction", roundRequest.getReconstructionResult() != null,
                "stage", safe(roundRequest.getStage())
        ));
        auditHook(sessionId, planId, nodeId, "recall", triggerSource, Map.of(
                "hasNodeWorkset", roundRequest.getNodeWorksetResult() != null,
                "hasExecutionCandidates", roundRequest.getExecutionCandidates() != null && !roundRequest.getExecutionCandidates().isEmpty(),
                "hasMcpHints", roundRequest.getMcpResourceHints() != null && !roundRequest.getMcpResourceHints().isEmpty()
        ));
        auditHook(sessionId, planId, nodeId, "rerank", triggerSource, Map.of(
                "hasRerankResult", roundRequest.getNodeWorksetResult() != null
                        && roundRequest.getNodeWorksetResult().getRerankResult() != null
        ));
        auditHook(sessionId, planId, nodeId, "assemble", triggerSource, Map.of(
                "runMainModel", roundRequest.isRunMainModel(),
                "writeRoundState", roundRequest.isWriteRoundState()
        ));
        RoundPipelineResult result = roundPipelineOrchestrator.executeRound(roundRequest);
        auditHook(sessionId, planId, nodeId, "execute", triggerSource, Map.of(
                "blocked", result != null && result.isBlocked(),
                "blockedReason", result == null ? "round_result_missing" : safe(result.getBlockedReason())
        ));
        auditHook(sessionId, planId, nodeId, "writeback", triggerSource, Map.of(
                "finalSnapshotId", result == null ? "" : safe(result.getFinalSnapshotId()),
                "summaryPresent", result != null && result.getSummaryResult() != null
        ));
        return result;
    }

    private void auditHook(String sessionId,
                           Long planId,
                           Long nodeId,
                           String hookName,
                           String triggerSource,
                           Map<String, Object> payload) {
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "STATE_DRIVEN_CONTEXT_PIPELINE_HOOK",
                hookName + " hook executed",
                toJsonSafe(Map.of(
                        "hook", hookName,
                        "triggerSource", triggerSource,
                        "payload", payload
                ))
        );
    }

    private Long contextPlanId(RoundPipelineRequest request) {
        try {
            if (request == null || request.getContextPackage() == null || request.getContextPackage().getRuntime() == null) {
                return null;
            }
            Object session = request.getContextPackage().getRuntime().get("session");
            if (session instanceof Map<?, ?> row) {
                return toLong(row.get("current_plan_id"));
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextNodeId(RoundPipelineRequest request) {
        try {
            if (request == null || request.getContextPackage() == null || request.getContextPackage().getTaskContext() == null) {
                return null;
            }
            Object working = request.getContextPackage().getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> row) {
                return toLong(row.get("active_node_id"));
            }
            return null;
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

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
