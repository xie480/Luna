package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.RoundPipelineOrchestrator;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.MainModelExecutionRequest;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.RoundStateWriteRequest;
import org.yilena.luna.service.model.RoundToolSemanticRequest;
import org.yilena.luna.service.model.SummaryOrchestrationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoundPipelineOrchestratorImpl implements RoundPipelineOrchestrator {

    private final TaskOrchestratorService taskOrchestratorService;
    private final ToolSemanticAgent toolSemanticAgent;
    private final ToolSemanticResultValidator toolSemanticResultValidator;
    private final ToolSemanticTraceLogger toolSemanticTraceLogger;
    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;
    private final StateTransitionTraceLogger stateTransitionTraceLogger;

    @Override
    public ToolSemanticResult resolveToolSemantic(RoundToolSemanticRequest request) {
        if (request == null) {
            return fallbackToolSemanticResult("agent_tool_chain", "", "", "round_tool_semantic_request_missing");
        }
        StructuredContextPackage contextPackage = request.getContextPackage();
        Long planId = contextPlanId(contextPackage);
        Long nodeId = contextNodeId(contextPackage);
        String toolName = firstNonBlank(request.getToolName(), resolvePrimaryToolName(request.getExecutionCandidates()));
        String toolDescription = firstNonBlank(request.getToolDescription(), resolvePrimaryToolDescription(request.getExecutionCandidates()));
        String explicitGoal = nullSafe(request.getExplicitTaskGoal());
        TaskRuntimeState taskState = request.getTaskState() == null
                ? (contextPackage == null ? null : contextPackage.getTaskState())
                : request.getTaskState();
        String stage = nullSafe(request.getStage());
        ToolSemanticResult translated;
        try {
            translated = toolSemanticAgent.translate(
                    toolName,
                    toolDescription,
                    nullSafe(request.getToolContext()),
                    taskState,
                    explicitGoal
            );
        } catch (Exception ex) {
            translated = fallbackToolSemanticResult(
                    toolName,
                    toolDescription,
                    request.getToolContext(),
                    ex.getMessage()
            );
        }
        if (translated == null || Boolean.TRUE.equals(safeMap(translated.getSemanticPayload()).get("semantic_translation_failed"))) {
            Object failureReason = translated == null ? null : safeMap(translated.getSemanticPayload()).get("failure_reason");
            translated = fallbackToolSemanticResult(
                    toolName,
                    toolDescription,
                    request.getToolContext(),
                    translated == null ? "tool_semantic_translation_empty" : (failureReason == null ? "" : String.valueOf(failureReason))
            );
        }
        translated = validateAndTraceToolSemantic(
                nullSafe(request.getSessionId()),
                planId,
                nodeId,
                stage,
                translated,
                contextPackage,
                safeMap(request.getRawToolResultChannel())
        );
        return translated;
    }

    @Override
    public RoundPipelineResult executeRound(RoundPipelineRequest request) {
        if (request == null) {
            return RoundPipelineResult.builder()
                    .blocked(true)
                    .blockedReason("round_request_missing")
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
        String sessionId = nullSafe(request.getSessionId());
        StructuredContextPackage contextPackage = request.getContextPackage();
        Long planId = contextPlanId(contextPackage);
        Long nodeId = contextNodeId(contextPackage);
        String traceId = "round_pipeline:" + sessionId + ":" + System.currentTimeMillis();
        NodeWorksetResult nodeWorksetResult = request.getNodeWorksetResult();
        ContextRerankResult rerankResult = nodeWorksetResult == null ? null : nodeWorksetResult.getRerankResult();
        List<Resource> executionCandidates = request.getExecutionCandidates() == null
                ? (nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null ? List.of() : nodeWorksetResult.getExecutionCandidates())
                : request.getExecutionCandidates();
        List<String> mcpResourceHints = request.getMcpResourceHints() == null
                ? (nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null ? List.of() : nodeWorksetResult.getMcpResourceHints())
                : request.getMcpResourceHints();
        List<EvidenceBlock> knowledgeEvidenceBlocks = nodeWorksetResult == null || nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks() == null
                ? List.of()
                : nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks();

        ToolSemanticResult effectiveToolSemantic = request.getToolSemanticResult();
        if (effectiveToolSemantic == null) {
            effectiveToolSemantic = resolveToolSemantic(RoundToolSemanticRequest.builder()
                    .sessionId(sessionId)
                    .contextPackage(contextPackage)
                    .taskState(request.getDecision() == null ? null : request.getDecision().getTaskState())
                    .explicitTaskGoal(request.getReconstructionResult() == null ? null : request.getReconstructionResult().getExplicitTaskGoal())
                    .executionCandidates(executionCandidates)
                    .toolContext(request.getToolContext())
                    .stage(firstNonBlank(request.getStage(), "ROUND"))
                    .rawToolResultChannel(safeMap(request.getRawToolResultChannel()))
                    .build());
        }

        SummaryResult preAssemblySummary = null;
        MainModelOrchestrationResult modelResult = null;
        String assistantReply = nullSafe(request.getAssistantReplyOverride());
        String finalSnapshotId = nullSafe(request.getLatestSnapshotId());

        if (request.isRunMainModel()) {
            SummaryOrchestrationResult preSummaryResult = taskOrchestratorService.orchestrateSummary(
                    sessionId,
                    nullSafe(request.getUserInput()),
                    "",
                    contextPackage,
                    knowledgeEvidenceBlocks,
                    mcpResourceHints,
                    effectiveToolSemantic,
                    false,
                    firstNonBlank(request.getPreAssemblyTriggerSource(), "ROUND_PRE_ASSEMBLY")
            );
            preAssemblySummary = preSummaryResult == null ? null : preSummaryResult.getSummaryResult();
            modelResult = taskOrchestratorService.orchestrateMainModel(
                    MainModelExecutionRequest.builder()
                            .sessionId(sessionId)
                            .userInput(nullSafe(request.getUserInput()))
                            .contextPackage(contextPackage)
                            .reconstructionResult(request.getReconstructionResult())
                            .rerankResult(rerankResult)
                            .toolSemanticResult(effectiveToolSemantic)
                            .knowledgeEvidenceBlocks(knowledgeEvidenceBlocks)
                            .workingMemorySnippets(safeList(request.getWorkingMemorySnippets()))
                            .runtimeMemorySnippets(safeList(request.getRuntimeMemorySnippets()))
                            .retrievedMemorySnippets(safeList(request.getRetrievedMemorySnippets()))
                            .knowledgeSnippets(safeList(request.getKnowledgeSnippets()))
                            .preferenceSnippets(safeList(request.getPreferenceSnippets()))
                            .longTermMemorySnippets(safeList(request.getLongTermMemorySnippets()))
                            .executionCandidates(executionCandidates)
                            .mcpResourceHints(mcpResourceHints)
                            .toolContext(nullSafe(request.getToolContext()))
                            .nodeTemplatePolicy(request.getNodeTemplatePolicy())
                            .roundSummaryInput(preAssemblySummary)
                            .planId(contextPlanId(contextPackage))
                            .nodeId(contextNodeId(contextPackage))
                            .stage(firstNonBlank(request.getStage(), "ROUND"))
                            .repairSeed(firstNonBlank(request.getRepairSeed(), request.getUserInput()))
                            .rawToolResultChannel(safeMap(request.getRawToolResultChannel()))
                            .build()
            );
            if (modelResult == null || modelResult.isBlocked()) {
                stateTransitionTraceLogger.log(
                        traceId,
                        sessionId,
                        planId,
                        nodeId,
                        request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                        request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                        firstNonBlank(request.getStage(), "ROUND"),
                        "execute",
                        modelResult == null ? "" : firstNonBlank(modelResult.getFinalSnapshotId(), ""),
                        contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
                );
                return RoundPipelineResult.builder()
                        .blocked(true)
                        .blockedReason(modelResult == null ? "main_model_result_missing" : nullSafe(modelResult.getBlockedReason()))
                        .toolSemanticResult(effectiveToolSemantic)
                        .preAssemblySummary(preAssemblySummary)
                        .mainModelResult(modelResult)
                        .summaryResult(null)
                        .finalSnapshotId(modelResult == null ? finalSnapshotId : firstNonBlank(modelResult.getFinalSnapshotId(), finalSnapshotId))
                        .decision(request.getDecision())
                        .contextPackage(contextPackage)
                        .reconstructionResult(request.getReconstructionResult())
                        .nodeWorksetResult(nodeWorksetResult)
                        .build();
            }
            assistantReply = nullSafe(modelResult.getReplyText());
            finalSnapshotId = firstNonBlank(modelResult.getFinalSnapshotId(), finalSnapshotId);
        }

        SummaryOrchestrationResult postSummary = taskOrchestratorService.orchestrateSummary(
                sessionId,
                nullSafe(request.getUserInput()),
                assistantReply,
                contextPackage,
                knowledgeEvidenceBlocks,
                mcpResourceHints,
                effectiveToolSemantic,
                request.isReplaceHistoryWithSummary(),
                firstNonBlank(request.getPostSummaryTriggerSource(), "ROUND")
        );
        SummaryResult summaryResult = postSummary == null ? null : postSummary.getSummaryResult();

        if (request.isWriteRoundState()) {
            taskOrchestratorService.writeRoundState(RoundStateWriteRequest.builder()
                    .sessionId(sessionId)
                    .decision(request.getDecision())
                    .contextPackage(contextPackage)
                    .reconstruction(request.getReconstructionResult())
                    .rerankResult(rerankResult)
                    .toolSemanticResult(effectiveToolSemantic)
                    .summaryResult(summaryResult)
                    .latestSnapshotId(finalSnapshotId)
                    .latestToolRawRef(nullSafe(request.getLatestToolRawRef()))
                    .latestToolHistoryRefs(safeList(request.getLatestToolHistoryRefs()))
                    .rawToolResultChannel(safeMap(request.getRawToolResultChannel()))
                    .ragQuery(nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getRagQuery()))
                    .memoryQuery(nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getMemoryQuery()))
                    .mcpQuery(nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getMcpDrivenInput()))
                    .retrievalPlanOverrides(safeMap(request.getRetrievalPlanOverrides()))
                    .build());
        }
        stateTransitionTraceLogger.log(
                traceId,
                sessionId,
                planId,
                nodeId,
                request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                firstNonBlank(request.getStage(), "ROUND"),
                "writeback",
                finalSnapshotId,
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        return RoundPipelineResult.builder()
                .blocked(false)
                .blockedReason("")
                .toolSemanticResult(effectiveToolSemantic)
                .preAssemblySummary(preAssemblySummary)
                .mainModelResult(modelResult)
                .summaryResult(summaryResult)
                .finalSnapshotId(finalSnapshotId)
                .decision(request.getDecision())
                .contextPackage(contextPackage)
                .reconstructionResult(request.getReconstructionResult())
                .nodeWorksetResult(nodeWorksetResult)
                .build();
    }

    private ToolSemanticResult validateAndTraceToolSemantic(String sessionId,
                                                            Long planId,
                                                            Long nodeId,
                                                            String stage,
                                                            ToolSemanticResult translated,
                                                            StructuredContextPackage contextPackage,
                                                            Map<String, Object> rawToolResultChannel) {
        ToolSemanticResult safeTranslated = translated == null
                ? fallbackToolSemanticResult("agent_tool_chain", "", "", "tool_semantic_translation_empty")
                : translated;
        ToolSemanticResultValidator.ValidationResult validationResult = toolSemanticResultValidator.validate(safeTranslated, contextPackage);
        if (validationResult.valid()) {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_VALIDATION",
                    firstNonBlank(stage, "ROUND") + " semantic validation passed",
                    "{}"
            );
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("issues", validationResult.issues());
            payload.put("stage", nullSafe(stage));
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_VALIDATION",
                    firstNonBlank(stage, "ROUND") + " semantic validation failed",
                    toJsonSafe(payload)
            );
            if (validationResult.issues() != null && validationResult.issues().contains("schema_invalid")) {
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        planId,
                        nodeId,
                        "TOOL_SEMANTIC_SCHEMA_INVALID",
                        "semantic result rejected by schema, normalized fallback applied",
                        toJsonSafe(payload)
                );
            }
        }
        if (validationResult.normalized() != null) {
            safeTranslated = validationResult.normalized();
        }
        String rawResultRef = resolveLatestRawResultRef(rawToolResultChannel, null);
        List<String> validationIssues = validationResult.issues() == null ? List.of() : validationResult.issues();
        String semanticTraceId = UUID.randomUUID().toString();
        Map<String, Object> semanticPipelinePayload = new LinkedHashMap<>();
        semanticPipelinePayload.put("traceId", semanticTraceId);
        semanticPipelinePayload.put("rawResultRef", rawResultRef);
        semanticPipelinePayload.put("rawDigest", nullSafe(safeTranslated.getRawResultDigest()));
        semanticPipelinePayload.put("semanticResult", safeTranslated);
        semanticPipelinePayload.put("validationIssues", validationIssues);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "TOOL_SEMANTIC_PIPELINE_TRACE",
                firstNonBlank(stage, "ROUND") + " semantic pipeline traced",
                toJsonSafe(semanticPipelinePayload)
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "TOOL_SEMANTIC_TRANSLATION",
                firstNonBlank(stage, "ROUND") + " tool semantic translated",
                toJsonSafe(safeTranslated)
        );
        toolSemanticTraceLogger.log(sessionId, planId, nodeId, safeTranslated);
        return safeTranslated;
    }

    @SuppressWarnings("unchecked")
    private String resolveLatestRawResultRef(Map<String, Object> rawToolResultChannel, String fallbackRef) {
        if (rawToolResultChannel != null && !rawToolResultChannel.isEmpty()) {
            Object latest = rawToolResultChannel.get("latestToolRawRef");
            if (latest != null && !String.valueOf(latest).isBlank()) {
                return String.valueOf(latest);
            }
            Object refs = rawToolResultChannel.get("toolHistoryRefs");
            if (refs instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first != null && !String.valueOf(first).isBlank()) {
                    return String.valueOf(first);
                }
            }
        }
        if (fallbackRef != null && !fallbackRef.isBlank()) {
            return fallbackRef;
        }
        return "tool_execution_trace:latest";
    }

    private ToolSemanticResult fallbackToolSemanticResult(String toolName,
                                                          String toolDescription,
                                                          String rawToolResult,
                                                          String errorMessage) {
        return ToolSemanticResult.builder()
                .toolName(firstNonBlank(toolName, "agent_tool_chain"))
                .toolDescription(nullSafe(toolDescription))
                .rawResultDigest(truncate(rawToolResult, 640))
                .toolStatus("UNKNOWN")
                .keyFacts(List.of("semantic_translation_failed"))
                .businessImpact("semantic_translation_unavailable_raw_channel_only")
                .unresolvedIssues(errorMessage == null || errorMessage.isBlank()
                        ? List.of("semantic_translation_failed")
                        : List.of(truncate(errorMessage, 200)))
                .nextStepHint("retry_or_recover")
                .confidence(0.0)
                .semanticPayload(Map.of(
                        "status", "UNKNOWN",
                        "tool", firstNonBlank(toolName, ""),
                        "raw_channel_only", true,
                        "semantic_translation_failed", true,
                        "failure_reason", errorMessage == null ? "" : truncate(errorMessage, 200)
                ))
                .build();
    }

    private String resolvePrimaryToolName(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "agent_tool_chain";
        }
        Resource first = executionCandidates.get(0);
        return first == null || first.getName() == null || first.getName().isBlank()
                ? "agent_tool_chain"
                : first.getName();
    }

    private String resolvePrimaryToolDescription(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "";
        }
        Resource first = executionCandidates.get(0);
        if (first == null) {
            return "";
        }
        return "type=" + (first.getType() == null ? "" : first.getType().name())
                + ", server=" + firstNonBlank(first.getServerCode(), "local")
                + ", name=" + firstNonBlank(first.getName(), "");
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String text, int maxLen) {
        String normalized = nullSafe(text);
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen));
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
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignore) {
            return null;
        }
    }
}
