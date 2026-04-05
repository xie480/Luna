package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.RoundPipelineOrchestrator;
import org.yilena.luna.service.StateDrivenContextPipeline;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;
import org.yilena.luna.service.model.TaskOrchestrationResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StateDrivenContextPipelineImpl implements StateDrivenContextPipeline {

    private final RoundPipelineOrchestrator roundPipelineOrchestrator;
    private final RuntimeAuditService runtimeAuditService;
    private final TaskOrchestratorService taskOrchestratorService;
    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ObjectMapper objectMapper;
    private final StateTransitionTraceLogger stateTransitionTraceLogger;

    @Override
    public RoundPipelineResult run(StateDrivenContextPipelineRequest request) {
        if (request == null || request.getRoundPipelineRequest() == null) {
            return blocked("state_driven_context_pipeline_request_missing");
        }
        RoundPipelineRequest hydratedRoundRequest = hydrateRoundRequest(request);
        if (hydratedRoundRequest == null) {
            return blocked("state_driven_context_pipeline_hydration_failed");
        }

        String sessionId = firstNonBlank(request.getSessionId(), hydratedRoundRequest.getSessionId());
        String triggerSource = firstNonBlank(request.getTriggerSource(), "STATE_DRIVEN_CONTEXT_PIPELINE");
        Long planId = contextPlanId(hydratedRoundRequest);
        Long nodeId = contextNodeId(hydratedRoundRequest);
        String traceId = "state_pipeline:" + sessionId + ":" + System.currentTimeMillis();

        auditHook(traceId, sessionId, planId, nodeId, "reconstruct", triggerSource, hydratedRoundRequest, Map.of(
                "hasReconstruction", hydratedRoundRequest.getReconstructionResult() != null,
                "stage", safe(hydratedRoundRequest.getStage())
        ));
        auditHook(traceId, sessionId, planId, nodeId, "recall", triggerSource, hydratedRoundRequest, Map.of(
                "hasNodeWorkset", hydratedRoundRequest.getNodeWorksetResult() != null,
                "hasExecutionCandidates", hydratedRoundRequest.getExecutionCandidates() != null && !hydratedRoundRequest.getExecutionCandidates().isEmpty(),
                "hasMcpHints", hydratedRoundRequest.getMcpResourceHints() != null && !hydratedRoundRequest.getMcpResourceHints().isEmpty()
        ));
        auditHook(traceId, sessionId, planId, nodeId, "rerank", triggerSource, hydratedRoundRequest, Map.of(
                "hasRerankResult", hydratedRoundRequest.getNodeWorksetResult() != null
                        && hydratedRoundRequest.getNodeWorksetResult().getRerankResult() != null
        ));
        auditHook(traceId, sessionId, planId, nodeId, "assemble", triggerSource, hydratedRoundRequest, Map.of(
                "runMainModel", hydratedRoundRequest.isRunMainModel(),
                "writeRoundState", hydratedRoundRequest.isWriteRoundState()
        ));

        RoundPipelineResult result = roundPipelineOrchestrator.executeRound(hydratedRoundRequest);
        auditHook(traceId, sessionId, planId, nodeId, "execute", triggerSource, hydratedRoundRequest, Map.of(
                "blocked", result != null && result.isBlocked(),
                "blockedReason", result == null ? "round_result_missing" : safe(result.getBlockedReason())
        ));
        auditHook(traceId, sessionId, planId, nodeId, "writeback", triggerSource, hydratedRoundRequest, Map.of(
                "finalSnapshotId", result == null ? "" : safe(result.getFinalSnapshotId()),
                "summaryPresent", result != null && result.getSummaryResult() != null
        ));
        if (result == null) {
            return blocked("round_result_missing");
        }
        return RoundPipelineResult.builder()
                .blocked(result.isBlocked())
                .blockedReason(result.getBlockedReason())
                .toolSemanticResult(result.getToolSemanticResult())
                .preAssemblySummary(result.getPreAssemblySummary())
                .mainModelResult(result.getMainModelResult())
                .summaryResult(result.getSummaryResult())
                .finalSnapshotId(result.getFinalSnapshotId())
                .decision(result.getDecision() == null ? hydratedRoundRequest.getDecision() : result.getDecision())
                .contextPackage(result.getContextPackage() == null ? hydratedRoundRequest.getContextPackage() : result.getContextPackage())
                .reconstructionResult(result.getReconstructionResult() == null ? hydratedRoundRequest.getReconstructionResult() : result.getReconstructionResult())
                .nodeWorksetResult(result.getNodeWorksetResult() == null ? hydratedRoundRequest.getNodeWorksetResult() : result.getNodeWorksetResult())
                .build();
    }

    private RoundPipelineResult blocked(String reason) {
        return RoundPipelineResult.builder()
                .blocked(true)
                .blockedReason(reason)
                .toolSemanticResult(null)
                .preAssemblySummary(null)
                .mainModelResult(null)
                .summaryResult(null)
                .finalSnapshotId("")
                .decision(null)
                .contextPackage(null)
                .reconstructionResult(null)
                .nodeWorksetResult(null)
                .build();
    }

    private RoundPipelineRequest hydrateRoundRequest(StateDrivenContextPipelineRequest request) {
        RoundPipelineRequest input = request.getRoundPipelineRequest();
        if (input == null) {
            return null;
        }
        String sessionId = firstNonBlank(request.getSessionId(), input.getSessionId());
        String userInput = safe(input.getUserInput());

        OrchestrationDecision decision = input.getDecision();
        StructuredContextPackage contextPackage = input.getContextPackage();
        InputReconstructionResult reconstructionResult = input.getReconstructionResult();

        if ((decision == null || contextPackage == null || reconstructionResult == null) && !userInput.isBlank()) {
            TaskOrchestrationResult orchestration = taskOrchestratorService.orchestrateUserInput(sessionId, userInput);
            if (decision == null) {
                decision = orchestration == null ? null : orchestration.getDecision();
            }
            if (contextPackage == null) {
                contextPackage = orchestration == null ? null : orchestration.getContextPackage();
            }
            if (reconstructionResult == null) {
                reconstructionResult = orchestration == null ? null : orchestration.getReconstructionResult();
            }
        }

        NodeWorksetResult nodeWorksetResult = input.getNodeWorksetResult();
        if (nodeWorksetResult == null
                && !userInput.isBlank()
                && decision != null
                && contextPackage != null
                && reconstructionResult != null) {
            nodeWorksetResult = taskOrchestratorService.orchestrateNodeWorkset(
                    sessionId,
                    userInput,
                    decision,
                    contextPackage,
                    reconstructionResult
            );
        }

        List<Resource> executionCandidates = nonEmpty(input.getExecutionCandidates())
                ? input.getExecutionCandidates()
                : (nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null ? List.of() : nodeWorksetResult.getExecutionCandidates());
        List<String> mcpResourceHints = nonEmpty(input.getMcpResourceHints())
                ? input.getMcpResourceHints()
                : (nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null ? List.of() : nodeWorksetResult.getMcpResourceHints());

        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        if (nodeWorksetResult != null && nonEmpty(nodeWorksetResult.getSelectedKnowledgeSnippets())) {
            knowledgeSnippets = nodeWorksetResult.getSelectedKnowledgeSnippets();
        }
        if (nonEmpty(input.getKnowledgeSnippets())) {
            knowledgeSnippets = input.getKnowledgeSnippets();
        }

        List<String> preferenceSnippets = mergeDistinct(
                extractRelationalPreferenceSnippets(contextPackage),
                nodeWorksetResult == null ? List.of() : nodeWorksetResult.getSelectedPreferenceSnippets()
        );
        if (nonEmpty(input.getPreferenceSnippets())) {
            preferenceSnippets = input.getPreferenceSnippets();
        }

        List<String> longTermMemorySnippets = nonEmpty(input.getLongTermMemorySnippets())
                ? input.getLongTermMemorySnippets()
                : extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = nonEmpty(input.getWorkingMemorySnippets())
                ? input.getWorkingMemorySnippets()
                : extractWorkingMemorySnippets(contextPackage);
        List<String> runtimeMemorySnippets = nonEmpty(input.getRuntimeMemorySnippets())
                ? input.getRuntimeMemorySnippets()
                : extractRuntimeMessageSnippets(contextPackage);
        List<String> retrievedMemorySnippets = nonEmpty(input.getRetrievedMemorySnippets())
                ? input.getRetrievedMemorySnippets()
                : (nodeWorksetResult == null || nodeWorksetResult.getSelectedMemorySnippets() == null ? List.of() : nodeWorksetResult.getSelectedMemorySnippets());

        ContextNodeTemplatePolicy nodeTemplatePolicy = input.getNodeTemplatePolicy() == null
                ? resolveNodeTemplatePolicy(decision, contextPackage)
                : input.getNodeTemplatePolicy();

        return RoundPipelineRequest.builder()
                .sessionId(sessionId)
                .userInput(userInput)
                .decision(decision)
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .nodeWorksetResult(nodeWorksetResult)
                .toolSemanticResult(input.getToolSemanticResult())
                .workingMemorySnippets(workingMemorySnippets)
                .runtimeMemorySnippets(runtimeMemorySnippets)
                .retrievedMemorySnippets(retrievedMemorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .preferenceSnippets(preferenceSnippets)
                .longTermMemorySnippets(longTermMemorySnippets)
                .executionCandidates(executionCandidates)
                .mcpResourceHints(mcpResourceHints)
                .nodeTemplatePolicy(nodeTemplatePolicy)
                .toolContext(safe(input.getToolContext()))
                .stage(firstNonBlank(input.getStage(), firstNonBlank(request.getTriggerSource(), "ROUND")))
                .repairSeed(firstNonBlank(input.getRepairSeed(), userInput))
                .runMainModel(input.isRunMainModel())
                .assistantReplyOverride(safe(input.getAssistantReplyOverride()))
                .preAssemblyTriggerSource(firstNonBlank(input.getPreAssemblyTriggerSource(), "PRE_ASSEMBLY_INPUT"))
                .postSummaryTriggerSource(firstNonBlank(input.getPostSummaryTriggerSource(), firstNonBlank(request.getTriggerSource(), "ROUND")))
                .replaceHistoryWithSummary(input.isReplaceHistoryWithSummary())
                .writeRoundState(input.isWriteRoundState())
                .latestSnapshotId(safe(input.getLatestSnapshotId()))
                .latestToolRawRef(safe(input.getLatestToolRawRef()))
                .latestToolHistoryRefs(input.getLatestToolHistoryRefs() == null ? List.of() : input.getLatestToolHistoryRefs())
                .rawToolResultChannel(input.getRawToolResultChannel() == null ? Map.of() : input.getRawToolResultChannel())
                .retrievalPlanOverrides(input.getRetrievalPlanOverrides() == null ? Map.of() : input.getRetrievalPlanOverrides())
                .build();
    }

    private void auditHook(String traceId,
                           String sessionId,
                           Long planId,
                           Long nodeId,
                           String hookName,
                           String triggerSource,
                           RoundPipelineRequest request,
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
        stateTransitionTraceLogger.log(
                traceId,
                sessionId,
                planId,
                nodeId,
                request == null || request.getDecision() == null || request.getDecision().getTaskState() == null
                        ? ""
                        : request.getDecision().getTaskState().name(),
                request == null || request.getDecision() == null || request.getDecision().getTaskState() == null
                        ? ""
                        : request.getDecision().getTaskState().name(),
                triggerSource,
                hookName,
                request == null ? "" : safe(request.getLatestSnapshotId()),
                request == null || request.getContextPackage() == null || request.getContextPackage().getRecoveryState() == null
                        ? ""
                        : safe(request.getContextPackage().getRecoveryState().getRecoveryEvent())
        );
    }

    private ContextNodeTemplatePolicy resolveNodeTemplatePolicy(OrchestrationDecision decision, StructuredContextPackage contextPackage) {
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        if (taskState == null && contextPackage != null) {
            taskState = contextPackage.getTaskState();
        }
        String currentNode = "";
        if (contextPackage != null && contextPackage.getTaskStateEntity() != null && contextPackage.getTaskStateEntity().getCurrentNode() != null) {
            currentNode = contextPackage.getTaskStateEntity().getCurrentNode();
        }
        String nodeKind = resolveCurrentNodeKind(contextPackage);
        return ContextNodeTemplatePolicy.forTaskNode(taskState, currentNode, nodeKind);
    }

    private String resolveCurrentNodeKind(StructuredContextPackage contextPackage) {
        Long planId = contextPlanId(contextPackage);
        Long nodeId = contextNodeId(contextPackage);
        if (planId == null || nodeId == null) {
            return "";
        }
        try {
            String nodeType = sessionRuntimeMapper.selectNodeTypeByPlanAndNode(planId, nodeId);
            return nodeType == null ? "" : nodeType.trim();
        } catch (Exception ignore) {
            return "";
        }
    }

    private List<String> extractTaskKnowledgeSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("knowledge");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "title: " + safe(stringValue(item.get("title"))) + "\ncontent: " + safe(stringValue(item.get("chunk_text"))))
                .toList();
    }

    private List<String> extractTaskLongTermSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        List<String> snippets = new ArrayList<>();
        Object factsRaw = contextPackage.getTaskContext().get("task_facts");
        if (factsRaw instanceof List<?> facts) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) facts;
            snippets.addAll(rows.stream()
                    .map(item -> "task_fact: " + safe(stringValue(item.get("fact_key"))) + "=" + safe(stringValue(item.get("fact_value_text"))))
                    .toList());
        }
        Object episodesRaw = contextPackage.getTaskContext().get("task_episodes");
        if (episodesRaw instanceof List<?> episodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) episodes;
            snippets.addAll(rows.stream()
                    .map(item -> "task_episode: " + safe(stringValue(item.get("episode_type"))) + " | " + safe(stringValue(item.get("trajectory_summary"))))
                    .toList());
        }
        return snippets;
    }

    private List<String> extractWorkingMemorySnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("working_memory");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        out.add("working.goal_raw: " + safe(stringValue(map.get("goal_raw"))));
        out.add("working.goal_refined: " + safe(stringValue(map.get("goal_refined"))));
        out.add("working.unresolved_questions: " + safe(stringValue(map.get("unresolved_questions_json"))));
        out.add("working.risks: " + safe(stringValue(map.get("risks_json"))));
        return out;
    }

    private List<String> extractRelationalPreferenceSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRelationalContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRelationalContext().get("semantic_facts");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "relation_pref: " + safe(stringValue(item.get("fact_key"))) + "=" + safe(stringValue(item.get("fact_value_text"))))
                .toList();
    }

    private List<String> extractRuntimeMessageSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRuntime().get("recent_messages");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> safe(stringValue(item.get("role"))) + ": " + safe(stringValue(item.get("content_text"))))
                .toList();
    }

    private List<String> mergeDistinct(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    private boolean nonEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private Long contextPlanId(RoundPipelineRequest request) {
        return contextPlanId(request == null ? null : request.getContextPackage());
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getRuntime() == null) {
                if (contextPackage == null || contextPackage.getTaskStateEntity() == null) {
                    return null;
                }
                return toLong(contextPackage.getTaskStateEntity().getTaskId());
            }
            Object session = contextPackage.getRuntime().get("session");
            if (session instanceof Map<?, ?> row) {
                Long runtimePlan = toLong(row.get("current_plan_id"));
                if (runtimePlan != null) {
                    return runtimePlan;
                }
            }
            return contextPackage.getTaskStateEntity() == null ? null : toLong(contextPackage.getTaskStateEntity().getTaskId());
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextNodeId(RoundPipelineRequest request) {
        return contextNodeId(request == null ? null : request.getContextPackage());
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getTaskContext() == null) {
                if (contextPackage == null || contextPackage.getTaskStateEntity() == null) {
                    return null;
                }
                return toLong(contextPackage.getTaskStateEntity().getCurrentNode());
            }
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> row) {
                Long runtimeNode = toLong(row.get("active_node_id"));
                if (runtimeNode != null) {
                    return runtimeNode;
                }
            }
            return contextPackage.getTaskStateEntity() == null ? null : toLong(contextPackage.getTaskStateEntity().getCurrentNode());
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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
