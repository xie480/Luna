package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.Lexicon;
import org.yilena.luna.context.RecoveryContextAgent;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.ContextSnapshot;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextSnapshotStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
/**
 * 恢复上下文代理默认实现，负责在中断、审批、工具回调等恢复场景下重建上下文并判断是否需要刷新检索与重组装。
 */
public class DefaultRecoveryContextAgent implements RecoveryContextAgent {

    private static final String RECOVERY_DECISION_PROMPT = """
            You are Recovery Context Agent.
            Decide whether recovery should refresh RAG/MCP evidence after an interrupt event.
            Return strict JSON only:
            {
              "refreshRagNow": true/false,
              "refreshMcpNow": true/false,
              "reassembleNow": true/false,
              "reason": "...",
              "invalidatedEvidenceRefs": ["..."],
              "invalidatedCapabilityNames": ["..."],
              "invalidationReasonsByRef": {"ref":"reason"}
            }
            Rules:
            - Use the event + interruption reason + context/snapshot drift signals.
            - Be conservative for stale or failed tool/approval states.
            - If confidence is low, prefer reassembly=true.
            recoveryEvent=%s
            interruptReason=%s
            contextDigest=%s
            snapshotDigest=%s
            """;

    private final RecoveryStateStore recoveryStateStore;
    private final ContextSnapshotStore contextSnapshotStore;
    private final ObjectMapper objectMapper;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final RuntimeAuditService runtimeAuditService;
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;

    public DefaultRecoveryContextAgent(RecoveryStateStore recoveryStateStore,
                                       ContextSnapshotStore contextSnapshotStore,
                                       ObjectMapper objectMapper,
                                       LlmClientUtil llmClientUtil,
                                       GeminiProperty geminiProperty) {
        this(recoveryStateStore, contextSnapshotStore, objectMapper, llmClientUtil, geminiProperty, null);
    }

    @Autowired
    public DefaultRecoveryContextAgent(RecoveryStateStore recoveryStateStore,
                                       ContextSnapshotStore contextSnapshotStore,
                                       ObjectMapper objectMapper,
                                       LlmClientUtil llmClientUtil,
                                       GeminiProperty geminiProperty,
                                       RuntimeAuditService runtimeAuditService) {
        this.recoveryStateStore = recoveryStateStore;
        this.contextSnapshotStore = contextSnapshotStore;
        this.objectMapper = objectMapper;
        this.llmClientUtil = llmClientUtil;
        this.geminiProperty = geminiProperty;
        this.runtimeAuditService = runtimeAuditService;
    }

    @Override
    /**
     * 根据恢复事件和上下文快照重建运行时上下文，并补齐恢复状态与刷新标记。
     */
    public StructuredContextPackage recover(String sessionId,
                                            StructuredContextPackage contextPackage,
                                            String recoveryEvent,
                                            String interruptReason) {
        if (sessionId == null || sessionId.isBlank()) {
            return contextPackage;
        }
        /**
         * 先定位恢复快照并基于快照还原结构化上下文，
         * 尽量让恢复后的链路从最近一次稳定状态继续推进。
         */
        String requestedSnapshotId = resolveRecoverySnapshotId(contextPackage);
        ContextSnapshot snapshot = loadSnapshot(sessionId, requestedSnapshotId);
        StructuredContextPackage restoredContext = rebuildFromSnapshot(contextPackage, snapshot);
        /**
         * 结合中断事件、当前上下文与快照漂移情况判断是否需要刷新 RAG、MCP 或重新组装，
         * 避免恢复后继续使用过期证据。
         */
        RecoveryDecision decision = evaluateRecoveryDecision(recoveryEvent, interruptReason, restoredContext, snapshot);
        String resolvedSnapshotId = resolveSnapshotId(snapshot, requestedSnapshotId, sessionId);
        decision = enforceRecoveryConsistency(sessionId, snapshot, restoredContext, decision, resolvedSnapshotId);
        RecoveryState state = RecoveryState.builder()
                .interruptedAt(Instant.now().toString())
                .interruptReason(interruptReason == null ? "" : interruptReason)
                .recoveryEvent(recoveryEvent == null ? "UNKNOWN_RECOVERY" : recoveryEvent)
                .recoverySnapshotId(resolvedSnapshotId)
                .build();
        recoveryStateStore.save(sessionId, state);
        if (restoredContext == null) {
            return null;
        }
        /**
         * 将恢复决策回写到提示策略、检索计划和运行时上下文中，
         * 让后续编排链路感知当前处于恢复模式。
         */
        restoredContext.setPromptPolicy(mergePromptPolicy(restoredContext.getPromptPolicy(), decision, snapshot, resolvedSnapshotId));
        if (restoredContext.getRetrievalState() != null) {
            restoredContext.setRetrievalState(rebuildRetrievalState(restoredContext.getRetrievalState(), decision));
        }
        restoredContext.setRuntime(mergeRuntimeWithSnapshot(restoredContext.getRuntime(), snapshot, resolvedSnapshotId));
        restoredContext.setRecoveryState(state);
        return restoredContext;
    }

    private RecoveryDecision evaluateRecoveryDecision(String recoveryEvent,
                                                      String interruptReason,
                                                      StructuredContextPackage contextPackage,
                                                      ContextSnapshot snapshot) {
        /**
         * 优先使用模型判断恢复策略，
         * 若模型不可用则退化为本地规则决策，保证恢复流程不中断。
         */
        RecoveryDecision llmDecision = tryModelDecision(recoveryEvent, interruptReason, contextPackage, snapshot);
        if (llmDecision != null) {
            return llmDecision;
        }
        return evaluateWithRules(recoveryEvent, interruptReason, contextPackage, snapshot);
    }

    private RecoveryDecision tryModelDecision(String recoveryEvent,
                                              String interruptReason,
                                              StructuredContextPackage contextPackage,
                                              ContextSnapshot snapshot) {
        try {
            /**
             * 将恢复事件、打断原因和上下文/快照摘要交给模型，
             * 生成更细粒度的刷新与失效判断结果。
             */
            String promptTemplate = promptRegistryService == null
                    ? RECOVERY_DECISION_PROMPT
                    : promptRegistryService.resolvePromptValue("agent-local.recovery.default_v1", RECOVERY_DECISION_PROMPT);
            String prompt = promptTemplate.formatted(
                    recoveryEvent == null ? "" : recoveryEvent,
                    interruptReason == null ? "" : interruptReason,
                    buildContextDigest(contextPackage),
                    buildSnapshotDigest(snapshot)
            );
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(resolveSmallAgentModel())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.1)
                    .enablePromptInjectionCheck(false)
                    .build();
            LlmResponse response = llmClientUtil.generate(request);
            String content = response == null ? "" : response.getContent();
            if (content == null || content.isBlank()) {
                return null;
            }
            String normalized = stripFence(content);
            var node = objectMapper.readTree(normalized);
            boolean needRagRefresh = node.path("refreshRagNow").asBoolean(node.path("needRagRefresh").asBoolean(false));
            boolean needMcpRefresh = node.path("refreshMcpNow").asBoolean(node.path("needMcpRefresh").asBoolean(false));
            boolean needReassembly = node.path("reassembleNow").asBoolean(node.path("needReassembly").asBoolean(
                    needRagRefresh || needMcpRefresh || contextPackage == null || snapshot == null
            ));
            String reason = node.path("reason").asText("");
            if (reason.isBlank()) {
                reason = "llm_recovery_decision";
            }
            List<String> invalidatedEvidenceRefs = jsonArrayToList(node.path("invalidatedEvidenceRefs"));
            List<String> invalidatedCapabilityNames = jsonArrayToList(node.path("invalidatedCapabilityNames"));
            Map<String, String> invalidationReasonsByRef = jsonObjectToStringMap(node.path("invalidationReasonsByRef"));
            return new RecoveryDecision(
                    needRagRefresh,
                    needMcpRefresh,
                    needReassembly,
                    reason,
                    invalidatedEvidenceRefs,
                    invalidatedCapabilityNames,
                    invalidationReasonsByRef
            );
        } catch (Exception ignore) {
            return null;
        }
    }

    private RecoveryDecision evaluateWithRules(String recoveryEvent,
                                               String interruptReason,
                                               StructuredContextPackage contextPackage,
                                               ContextSnapshot snapshot) {
        /**
         * 当模型决策不可用时，基于超时、失败、数据漂移和等待态等信号做保守判断，
         * 宁可多一次重组装，也避免恢复到错误上下文。
         */
        String event = normalize(recoveryEvent);
        String reason = normalize(interruptReason);
        boolean eventDrivenMutation = containsAny(event, "tool_result", "approval", "system", "timer", "callback");
        boolean reasonTimeoutSignal = containsAny(reason, Lexicon.RECOVERY_TIMEOUT_KEYWORDS);
        boolean reasonFailureSignal = containsAny(reason, Lexicon.RECOVERY_FAILURE_KEYWORDS);
        boolean reasonMutationSignal = containsAny(reason, Lexicon.RECOVERY_DATA_MUTATION_KEYWORDS);
        boolean waitingRecoveryState = isWaitingRecoveryState(contextPackage);
        boolean toolPendingOrFailed = hasPendingOrFailedToolState(contextPackage);
        boolean snapshotDriftSignal = hasSnapshotDrift(contextPackage, snapshot);

        boolean needRagRefresh = eventDrivenMutation || reasonTimeoutSignal || snapshotDriftSignal || reasonMutationSignal;
        boolean needMcpRefresh = eventDrivenMutation || toolPendingOrFailed || reasonFailureSignal || snapshotDriftSignal;
        boolean needReassembly = needRagRefresh || needMcpRefresh || waitingRecoveryState || contextPackage == null || snapshot == null;
        List<String> invalidatedEvidenceRefs = needRagRefresh
                ? collectEvidenceRefs(contextPackage, snapshot)
                : List.of();
        List<String> invalidatedCapabilityNames = needMcpRefresh
                ? collectCapabilityNames(contextPackage, snapshot)
                : List.of();
        Map<String, String> invalidationReasonsByRef = buildInvalidationReasonMap(
                invalidatedEvidenceRefs,
                invalidatedCapabilityNames,
                reason.isBlank() ? "rule_based_recovery_fallback" : reason
        );
        String mergedReason = reason.isBlank() ? "rule_based_recovery_fallback" : reason;
        return new RecoveryDecision(
                needRagRefresh,
                needMcpRefresh,
                needReassembly,
                mergedReason,
                invalidatedEvidenceRefs,
                invalidatedCapabilityNames,
                invalidationReasonsByRef
        );
    }

    private boolean isWaitingRecoveryState(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskState() == null) {
            return false;
        }
        return contextPackage.getTaskState() == org.yilena.luna.enums.TaskRuntimeState.WAITING_APPROVAL
                || contextPackage.getTaskState() == org.yilena.luna.enums.TaskRuntimeState.WAITING_TOOL
                || contextPackage.getTaskState() == org.yilena.luna.enums.TaskRuntimeState.WAITING_USER;
    }

    private boolean hasPendingOrFailedToolState(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getToolState() == null) {
            return false;
        }
        String status = normalize(contextPackage.getToolState().getLastToolStatus());
        return "pending".equals(status) || "failed".equals(status) || "error".equals(status);
    }

    private boolean hasSnapshotDrift(StructuredContextPackage contextPackage, ContextSnapshot snapshot) {
        if (contextPackage == null || contextPackage.getContextState() == null || snapshot == null || snapshot.getPayload() == null) {
            return false;
        }
        List<String> contextKnowledge = contextPackage.getContextState().getActiveKnowledgeRefs() == null
                ? List.of()
                : contextPackage.getContextState().getActiveKnowledgeRefs();
        List<String> snapshotKnowledge = readSnapshotRefList(snapshot.getPayload(), "activeKnowledgeRefs");
        if (!matchesRefs(contextKnowledge, snapshotKnowledge)) {
            return true;
        }
        List<String> contextMcp = contextPackage.getContextState().getActiveMcpResourceRefs() == null
                ? List.of()
                : contextPackage.getContextState().getActiveMcpResourceRefs();
        List<String> snapshotMcp = readSnapshotRefList(snapshot.getPayload(), "activeMcpResourceRefs");
        return !matchesRefs(contextMcp, snapshotMcp);
    }

    private String resolveRecoverySnapshotId(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return "";
        }
        if (contextPackage.getRecoveryState() != null
                && contextPackage.getRecoveryState().getRecoverySnapshotId() != null
                && !contextPackage.getRecoveryState().getRecoverySnapshotId().isBlank()) {
            return contextPackage.getRecoveryState().getRecoverySnapshotId();
        }
        if (contextPackage.getContextState() != null
                && contextPackage.getContextState().getLatestContextSnapshotId() != null
                && !contextPackage.getContextState().getLatestContextSnapshotId().isBlank()) {
            return contextPackage.getContextState().getLatestContextSnapshotId();
        }
        return "";
    }

    private ContextSnapshot loadSnapshot(String sessionId, String snapshotId) {
        if (snapshotId != null && !snapshotId.isBlank()) {
            ContextSnapshot byId = contextSnapshotStore.load(sessionId, snapshotId);
            if (byId != null) {
                return byId;
            }
        }
        return contextSnapshotStore.loadLatest(sessionId);
    }

    private String resolveSnapshotId(ContextSnapshot snapshot, String requestedSnapshotId, String sessionId) {
        if (snapshot != null && snapshot.getSnapshotId() != null && !snapshot.getSnapshotId().isBlank()) {
            return snapshot.getSnapshotId();
        }
        if (requestedSnapshotId != null && !requestedSnapshotId.isBlank()) {
            return requestedSnapshotId;
        }
        return sessionId + ":" + System.currentTimeMillis();
    }

    private StructuredContextPackage rebuildFromSnapshot(StructuredContextPackage current, ContextSnapshot snapshot) {
        /**
         * 尝试从快照中提取结构化上下文，并与当前运行态做合并，
         * 兼顾快照稳定性和当前链路中尚未持久化的新状态。
         */
        StructuredContextPackage snapshotPackage = extractStructuredContextPackage(snapshot);
        if (snapshotPackage == null) {
            return current;
        }
        return mergeContext(snapshotPackage, current);
    }

    private StructuredContextPackage extractStructuredContextPackage(ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayload() == null || snapshot.getPayload().isEmpty()) {
            return null;
        }
        Map<String, Object> payload = snapshot.getPayload();
        if (payload.containsKey("snapshotType")) {
            return extractTypedSnapshot(snapshot, payload);
        }
        if (!isStructuredPayload(payload)) {
            return null;
        }
        try {
            return objectMapper.convertValue(payload, StructuredContextPackage.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private StructuredContextPackage extractTypedSnapshot(ContextSnapshot snapshot, Map<String, Object> payload) {
        String type = safeType(payload.get("snapshotType"));
        if ("FINAL_MODEL_CONTEXT".equalsIgnoreCase(type)) {
            Map<String, Object> structuredRecoveryPayload = safeMap(payload.get("structuredRecoveryPayload"));
            if (!structuredRecoveryPayload.isEmpty()) {
                StructuredContextPackage structured = buildFromStructuredRecoveryPayload(snapshot, payload, structuredRecoveryPayload, type);
                if (structured != null) {
                    return structured;
                }
            }
            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("recovery_snapshot_type", type);
            runtime.put("recovery_prompt", payload.getOrDefault("prompt", ""));
            runtime.put("recovery_section_token_counts", payload.getOrDefault("sectionTokenCounts", Map.of()));
            runtime.put("recovery_section_token_ratios", payload.getOrDefault("sectionTokenRatios", Map.of()));
            runtime.put("recovery_active_refs", payload.getOrDefault("activeRefs", Map.of()));

            Map<String, Object> taskContext = new LinkedHashMap<>();
            Object sections = payload.get("sections");
            if (sections instanceof Map<?, ?> map) {
                taskContext.put("final_context_sections", map);
            }
            if (snapshot.getNodeId() != null) {
                taskContext.put("working_memory", Map.of("active_node_id", snapshot.getNodeId()));
            }

            return StructuredContextPackage.builder()
                    .sessionId(snapshot.getSessionId())
                    .runtime(runtime)
                    .taskContext(taskContext)
                    .recentMessages(recentMessagesFromSections(payload))
                    .contextState(ContextState.builder()
                            .latestNarrativeSummary("")
                            .latestStateSnapshot(Map.of())
                            .activeKnowledgeRefs(readSnapshotRefList(payload, "activeKnowledgeRefs"))
                            .activeMemoryRefs(readSnapshotRefList(payload, "activeMemoryRefs"))
                            .activeToolEvidenceRefs(readSnapshotRefList(payload, "activeToolEvidenceRefs"))
                            .activeMcpPromptRefs(readSnapshotRefList(payload, "activeMcpPromptRefs"))
                            .activeMcpResourceRefs(readSnapshotRefList(payload, "activeMcpResourceRefs"))
                            .activeMcpWorkflowRefs(readSnapshotRefList(payload, "activeMcpWorkflowRefs"))
                            .activeMcpToolRefs(resolveSnapshotToolRefs(payload))
                            .latestContextSnapshotId(snapshot.getSnapshotId() == null ? "" : snapshot.getSnapshotId())
                            .build())
                    .build();
        }
        if ("PRE_TOOL_DECISION_CONTEXT".equalsIgnoreCase(type)) {
            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("recovery_snapshot_type", type);
            runtime.put("recovery_user_input", payload.getOrDefault("userInput", ""));
            runtime.put("recovery_reconstructed_mcp_query", payload.getOrDefault("reconstructedMcpQuery", ""));
            runtime.put("recovery_execution_candidates", payload.getOrDefault("executionCandidates", List.of()));
            runtime.put("recovery_extra", payload.getOrDefault("extra", Map.of()));
            return StructuredContextPackage.builder()
                    .sessionId(snapshot.getSessionId())
                    .runtime(runtime)
                    .build();
        }
        if ("TOOL_DECISION_CONTEXT".equalsIgnoreCase(type)) {
            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("recovery_snapshot_type", type);
            runtime.put("recovery_assembled_decision_context", payload.getOrDefault("assembledDecisionContext", ""));
            runtime.put("recovery_sections", payload.getOrDefault("sections", Map.of()));
            runtime.put("recovery_execution_candidates", payload.getOrDefault("executionCandidates", List.of()));
            runtime.put("recovery_section_token_counts", payload.getOrDefault("sectionTokenCounts", Map.of()));
            runtime.put("recovery_section_token_ratios", payload.getOrDefault("sectionTokenRatios", Map.of()));
            runtime.put("recovery_extra", payload.getOrDefault("extra", Map.of()));
            return StructuredContextPackage.builder()
                    .sessionId(snapshot.getSessionId())
                    .runtime(runtime)
                    .build();
        }
        return null;
    }

    private StructuredContextPackage buildFromStructuredRecoveryPayload(ContextSnapshot snapshot,
                                                                        Map<String, Object> payload,
                                                                        Map<String, Object> structuredRecoveryPayload,
                                                                        String snapshotType) {
        try {
            TaskState taskState = parseTaskState(safeMap(structuredRecoveryPayload.get("taskState")));
            RetrievalState retrievalState = parseRetrievalState(safeMap(structuredRecoveryPayload.get("retrievalState")));
            ToolState toolState = parseToolState(safeMap(structuredRecoveryPayload.get("toolState")));
            ContextState contextState = parseContextState(safeMap(structuredRecoveryPayload.get("contextState")));
            RecoveryState recoveryState = parseRecoveryState(safeMap(structuredRecoveryPayload.get("recoveryState")));

            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("recovery_snapshot_type", snapshotType);
            runtime.put("recovery_prompt", payload.getOrDefault("prompt", ""));
            runtime.put("recovery_section_token_counts", payload.getOrDefault("sectionTokenCounts", Map.of()));
            runtime.put("recovery_section_token_ratios", payload.getOrDefault("sectionTokenRatios", Map.of()));
            runtime.put("recovery_active_refs", payload.getOrDefault("activeRefs", Map.of()));
            runtime.put("recovery_runtime_pointers", safeMap(structuredRecoveryPayload.get("runtimePointers")));

            Map<String, Object> taskContext = new LinkedHashMap<>();
            Object sections = payload.get("sections");
            if (sections instanceof Map<?, ?> map) {
                taskContext.put("final_context_sections", map);
            }
            if (snapshot.getNodeId() != null) {
                taskContext.put("working_memory", Map.of("active_node_id", snapshot.getNodeId()));
            }
            if (contextState != null && (contextState.getLatestContextSnapshotId() == null || contextState.getLatestContextSnapshotId().isBlank())) {
                contextState = ContextState.builder()
                        .latestNarrativeSummary(contextState.getLatestNarrativeSummary())
                        .latestStateSnapshot(contextState.getLatestStateSnapshot())
                        .activeKnowledgeRefs(contextState.getActiveKnowledgeRefs())
                        .activeMemoryRefs(contextState.getActiveMemoryRefs())
                        .activeToolEvidenceRefs(contextState.getActiveToolEvidenceRefs())
                        .activeMcpPromptRefs(contextState.getActiveMcpPromptRefs())
                        .activeMcpResourceRefs(contextState.getActiveMcpResourceRefs())
                        .activeMcpWorkflowRefs(contextState.getActiveMcpWorkflowRefs())
                        .activeMcpToolRefs(contextState.getActiveMcpToolRefs())
                        .latestContextSnapshotId(snapshot.getSnapshotId() == null ? "" : snapshot.getSnapshotId())
                        .build();
            }
            return StructuredContextPackage.builder()
                    .sessionId(snapshot.getSessionId())
                    .runtime(runtime)
                    .taskContext(taskContext)
                    .recentMessages(recentMessagesFromSections(payload))
                    .taskStateEntity(taskState)
                    .retrievalState(retrievalState)
                    .toolState(toolState)
                    .contextState(contextState)
                    .recoveryState(recoveryState)
                    .build();
        } catch (Exception ignore) {
            return null;
        }
    }

    private TaskState parseTaskState(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        return TaskState.builder()
                .taskId(stringValue(row.get("taskId")))
                .sessionId(stringValue(row.get("sessionId")))
                .objective(stringValue(row.get("objective")))
                .currentStage(stringValue(row.get("currentStage")))
                .currentNode(stringValue(row.get("currentNode")))
                .confirmedSlots(safeMap(row.get("confirmedSlots")))
                .pendingQuestions(toStringList(row.get("pendingQuestions")))
                .finishedSteps(toStringList(row.get("finishedSteps")))
                .failedSteps(toStringList(row.get("failedSteps")))
                .retryCount(toInteger(row.get("retryCount")))
                .nextActionHint(stringValue(row.get("nextActionHint")))
                .build();
    }

    private RetrievalState parseRetrievalState(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        return RetrievalState.builder()
                .reconstructedIntent(stringValue(row.get("reconstructedIntent")))
                .activeQueries(toStringList(row.get("activeQueries")))
                .retrievalPlan(safeMap(row.get("retrievalPlan")))
                .selectedEvidenceRefs(toStringList(row.get("selectedEvidenceRefs")))
                .rerankSummary(stringValue(row.get("rerankSummary")))
                .build();
    }

    private ToolState parseToolState(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        return ToolState.builder()
                .lastToolName(stringValue(row.get("lastToolName")))
                .lastToolInput(stringValue(row.get("lastToolInput")))
                .lastToolStatus(stringValue(row.get("lastToolStatus")))
                .lastToolRawResultRef(stringValue(row.get("lastToolRawResultRef")))
                .lastToolRawPayloadRef(stringValue(row.get("lastToolRawPayloadRef")))
                .lastToolRawResult(stringValue(row.get("lastToolRawResult")))
                .lastToolRawResultDigest(stringValue(row.get("lastToolRawResultDigest")))
                .lastToolRawResultPreview(stringValue(row.get("lastToolRawResultPreview")))
                .lastToolSemanticSummary(stringValue(row.get("lastToolSemanticSummary")))
                .toolCallHistoryRefs(toStringList(row.get("toolCallHistoryRefs")))
                .build();
    }

    private ContextState parseContextState(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        return ContextState.builder()
                .latestNarrativeSummary(stringValue(row.get("latestNarrativeSummary")))
                .latestStateSnapshot(safeMap(row.get("latestStateSnapshot")))
                .activeKnowledgeRefs(toStringList(row.get("activeKnowledgeRefs")))
                .activeMemoryRefs(toStringList(row.get("activeMemoryRefs")))
                .activeToolEvidenceRefs(toStringList(row.get("activeToolEvidenceRefs")))
                .activeMcpPromptRefs(toStringList(row.get("activeMcpPromptRefs")))
                .activeMcpResourceRefs(toStringList(row.get("activeMcpResourceRefs")))
                .activeMcpWorkflowRefs(toStringList(row.get("activeMcpWorkflowRefs")))
                .activeMcpToolRefs(toStringList(row.get("activeMcpToolRefs")))
                .latestContextSnapshotId(stringValue(row.get("latestContextSnapshotId")))
                .build();
    }

    private RecoveryState parseRecoveryState(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        return RecoveryState.builder()
                .interruptedAt(stringValue(row.get("interruptedAt")))
                .interruptReason(stringValue(row.get("interruptReason")))
                .recoveryEvent(stringValue(row.get("recoveryEvent")))
                .recoverySnapshotId(stringValue(row.get("recoverySnapshotId")))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recentMessagesFromSections(Map<String, Object> payload) {
        if (payload == null) {
            return List.of();
        }
        Object sectionsObj = payload.get("sections");
        if (!(sectionsObj instanceof Map<?, ?> sections)) {
            return List.of();
        }
        Object recentSectionObj = sections.get("Recent Interaction Context");
        if (!(recentSectionObj instanceof List<?> lines) || lines.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object lineObj : lines) {
            String line = lineObj == null ? "" : String.valueOf(lineObj).trim();
            if (line.isBlank()) {
                continue;
            }
            int split = line.indexOf(':');
            String role = split > 0 ? line.substring(0, split).trim() : "context";
            String content = split > 0 ? line.substring(split + 1).trim() : line;
            if (!content.isBlank()) {
                messages.add(Map.of("role", role, "content_text", content));
            }
        }
        return messages;
    }

    private String safeType(Object type) {
        return type == null ? "" : String.valueOf(type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private <T> T convertOrNull(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, type);
        } catch (Exception ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = stringValue(item);
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out.stream().distinct().toList();
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isStructuredPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        return payload.containsKey("sessionId")
                || payload.containsKey("taskState")
                || payload.containsKey("runtime")
                || payload.containsKey("taskContext")
                || payload.containsKey("relationalContext")
                || payload.containsKey("recentMessages");
    }

    private StructuredContextPackage mergeContext(StructuredContextPackage snapshot, StructuredContextPackage current) {
        if (snapshot == null) {
            return current;
        }
        if (current == null) {
            return snapshot;
        }
        return StructuredContextPackage.builder()
                .sessionId(firstNonBlank(current.getSessionId(), snapshot.getSessionId()))
                .taskState(current.getTaskState() == null ? snapshot.getTaskState() : current.getTaskState())
                .relationalState(current.getRelationalState() == null ? snapshot.getRelationalState() : current.getRelationalState())
                .runtime(preferSnapshotMap(snapshot.getRuntime(), current.getRuntime()))
                .taskContext(preferSnapshotMap(snapshot.getTaskContext(), current.getTaskContext()))
                .relationalContext(preferSnapshotMap(snapshot.getRelationalContext(), current.getRelationalContext()))
                .recentMessages(preferSnapshotList(snapshot.getRecentMessages(), current.getRecentMessages()))
                .capabilityCandidates(preferSnapshotList(snapshot.getCapabilityCandidates(), current.getCapabilityCandidates()))
                .promptPolicy(preferSnapshotMap(snapshot.getPromptPolicy(), current.getPromptPolicy()))
                .tokenBudgetPlan(preferSnapshotMap(snapshot.getTokenBudgetPlan(), current.getTokenBudgetPlan()))
                .taskStateEntity(snapshot.getTaskStateEntity() == null ? current.getTaskStateEntity() : snapshot.getTaskStateEntity())
                .retrievalState(snapshot.getRetrievalState() == null ? current.getRetrievalState() : snapshot.getRetrievalState())
                .toolState(snapshot.getToolState() == null ? current.getToolState() : snapshot.getToolState())
                .contextState(snapshot.getContextState() == null ? current.getContextState() : snapshot.getContextState())
                .recoveryState(current.getRecoveryState() == null ? snapshot.getRecoveryState() : current.getRecoveryState())
                .build();
    }

    @SuppressWarnings("unchecked")
    private <T> T preferSnapshotMap(T snapshotValue, T currentValue) {
        if (snapshotValue instanceof Map<?, ?> snapshotMap && currentValue instanceof Map<?, ?> currentMap) {
            Map<Object, Object> merged = new LinkedHashMap<>(currentMap);
            merged.putAll(snapshotMap);
            return (T) merged;
        }
        if (snapshotValue instanceof Map<?, ?> map && !map.isEmpty()) {
            return snapshotValue;
        }
        return currentValue;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> preferSnapshotList(List<T> snapshotValue, List<T> currentValue) {
        if (snapshotValue != null && !snapshotValue.isEmpty() && currentValue != null && !currentValue.isEmpty()) {
            List<T> merged = new ArrayList<>(snapshotValue);
            for (T item : currentValue) {
                if (!merged.contains(item)) {
                    merged.add(item);
                }
            }
            return merged;
        }
        if (snapshotValue != null && !snapshotValue.isEmpty()) {
            return snapshotValue;
        }
        if (currentValue == null) {
            return List.of();
        }
        return currentValue;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private Map<String, Object> mergePromptPolicy(Map<String, Object> current,
                                                  RecoveryDecision decision,
                                                  ContextSnapshot snapshot,
                                                  String snapshotId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        merged.put("recovery_required", decision.needReassembly());
        merged.put("refreshRagNow", decision.needRagRefresh());
        merged.put("refreshMcpNow", decision.needMcpRefresh());
        merged.put("reassembleNow", decision.needReassembly());
        merged.put("recovery_need_rag_refresh", decision.needRagRefresh());
        merged.put("recovery_need_mcp_refresh", decision.needMcpRefresh());
        merged.put("recovery_reason", decision.reason());
        merged.put("recovery_invalidated_evidence_refs", decision.invalidatedEvidenceRefs());
        merged.put("recovery_invalidated_capability_names", decision.invalidatedCapabilityNames());
        merged.put("recovery_invalidation_reasons_by_ref", decision.invalidationReasonsByRef());
        merged.put("recovery_snapshot_loaded", snapshot != null);
        merged.put("recovery_snapshot_id", snapshotId);
        merged.put("recovery_snapshot_type", snapshotType(snapshot));
        return merged;
    }

    private Map<String, Object> mergeRuntimeWithSnapshot(Map<String, Object> current,
                                                         ContextSnapshot snapshot,
                                                         String snapshotId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        merged.put("recovery_snapshot_id", snapshotId);
        if (snapshot != null) {
            merged.put("recovery_snapshot_plan_id", snapshot.getPlanId());
            merged.put("recovery_snapshot_node_id", snapshot.getNodeId());
            merged.put("recovery_snapshot_type", snapshotType(snapshot));
        }
        return merged;
    }

    private RecoveryDecision enforceRecoveryConsistency(String sessionId,
                                                        ContextSnapshot snapshot,
                                                        StructuredContextPackage restoredContext,
                                                        RecoveryDecision decision,
                                                        String resolvedSnapshotId) {
        if (decision == null) {
            return new RecoveryDecision(false, false, true, "recovery_decision_missing", List.of(), List.of(), Map.of());
        }
        List<String> issues = new ArrayList<>();
        String runtimeSnapshotId = "";
        if (restoredContext != null && restoredContext.getRuntime() != null) {
            runtimeSnapshotId = String.valueOf(restoredContext.getRuntime().getOrDefault("recovery_snapshot_id", ""));
        }
        if (!runtimeSnapshotId.isBlank()
                && !resolvedSnapshotId.isBlank()
                && !runtimeSnapshotId.equals(resolvedSnapshotId)) {
            issues.add("snapshot_id_mismatch");
        }
        Long snapshotNodeId = snapshot == null ? null : snapshot.getNodeId();
        Long contextNodeId = resolveContextNodeId(restoredContext);
        if (snapshotNodeId != null && contextNodeId != null && !snapshotNodeId.equals(contextNodeId)) {
            issues.add("node_id_mismatch");
        }
        if (hasActiveRefMismatch(snapshot, restoredContext)) {
            issues.add("active_refs_mismatch");
        }
        if (hasRecoveryFlagMismatch(restoredContext, decision)) {
            issues.add("retrieval_plan_recovery_flags_mismatch");
        }
        if (!issues.isEmpty()) {
            RecoveryDecision forced = new RecoveryDecision(
                    decision.needRagRefresh(),
                    decision.needMcpRefresh(),
                    true,
                    decision.reason() + "|consistency_forced_reassemble",
                    decision.invalidatedEvidenceRefs(),
                    decision.invalidatedCapabilityNames(),
                    decision.invalidationReasonsByRef()
            );
            auditRecoveryConsistency(sessionId, snapshot, resolvedSnapshotId, issues, true);
            return forced;
        }
        auditRecoveryConsistency(sessionId, snapshot, resolvedSnapshotId, List.of(), false);
        return decision;
    }

    private boolean hasActiveRefMismatch(ContextSnapshot snapshot, StructuredContextPackage restoredContext) {
        if (snapshot == null || snapshot.getPayload() == null) {
            return false;
        }
        ContextState contextState = restoredContext == null ? null : restoredContext.getContextState();
        if (contextState == null) {
            return false;
        }
        return !matchesRefs(readSnapshotRefList(snapshot.getPayload(), "activeKnowledgeRefs"), contextState.getActiveKnowledgeRefs())
                || !matchesRefs(readSnapshotRefList(snapshot.getPayload(), "activeMemoryRefs"), contextState.getActiveMemoryRefs())
                || !matchesRefs(readSnapshotRefList(snapshot.getPayload(), "activeToolEvidenceRefs"), contextState.getActiveToolEvidenceRefs())
                || !matchesRefs(readSnapshotRefList(snapshot.getPayload(), "activeMcpPromptRefs"), contextState.getActiveMcpPromptRefs())
                || !matchesRefs(readSnapshotRefList(snapshot.getPayload(), "activeMcpResourceRefs"), contextState.getActiveMcpResourceRefs())
                || !matchesRefs(readSnapshotRefList(snapshot.getPayload(), "activeMcpWorkflowRefs"), contextState.getActiveMcpWorkflowRefs())
                || !matchesRefs(resolveSnapshotToolRefs(snapshot.getPayload()), contextState.getActiveMcpToolRefs());
    }

    private boolean hasRecoveryFlagMismatch(StructuredContextPackage restoredContext, RecoveryDecision decision) {
        if (restoredContext == null || restoredContext.getRetrievalState() == null || restoredContext.getRetrievalState().getRetrievalPlan() == null) {
            return false;
        }
        Map<String, Object> retrievalPlan = restoredContext.getRetrievalState().getRetrievalPlan();
        boolean planRag = booleanValue(retrievalPlan.get("refreshRagNow")) || booleanValue(retrievalPlan.get("refresh_rag_now"));
        boolean planMcp = booleanValue(retrievalPlan.get("refreshMcpNow")) || booleanValue(retrievalPlan.get("refresh_mcp_now"));
        boolean planReassemble = booleanValue(retrievalPlan.get("reassembleNow")) || booleanValue(retrievalPlan.get("reassemble_now"));
        return planRag != decision.needRagRefresh()
                || planMcp != decision.needMcpRefresh()
                || planReassemble != decision.needReassembly();
    }

    private boolean matchesRefs(List<String> left, List<String> right) {
        java.util.LinkedHashSet<String> a = new java.util.LinkedHashSet<>(left == null ? List.of() : left.stream().filter(item -> item != null && !item.isBlank()).toList());
        java.util.LinkedHashSet<String> b = new java.util.LinkedHashSet<>(right == null ? List.of() : right.stream().filter(item -> item != null && !item.isBlank()).toList());
        return a.equals(b);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private Long resolveContextNodeId(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return null;
        }
        if (contextPackage.getTaskContext() != null) {
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> map) {
                Long node = toLong(map.get("active_node_id"));
                if (node != null) {
                    return node;
                }
            }
        }
        if (contextPackage.getTaskStateEntity() != null) {
            return toLong(contextPackage.getTaskStateEntity().getCurrentNode());
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

    private void auditRecoveryConsistency(String sessionId,
                                          ContextSnapshot snapshot,
                                          String resolvedSnapshotId,
                                          List<String> issues,
                                          boolean forcedReassemble) {
        if (runtimeAuditService == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                snapshot == null ? null : snapshot.getPlanId(),
                snapshot == null ? null : snapshot.getNodeId(),
                "RECOVERY_CONSISTENCY_CHECK",
                forcedReassemble ? "recovery consistency mismatch, forced reassembleNow=true" : "recovery consistency check passed",
                toJsonSafe(Map.of(
                        "snapshotId", resolvedSnapshotId == null ? "" : resolvedSnapshotId,
                        "forcedReassemble", forcedReassemble,
                        "issues", issues == null ? List.of() : issues
                ))
        );
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private String snapshotType(ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayload() == null) {
            return "UNKNOWN";
        }
        Object type = snapshot.getPayload().get("snapshotType");
        return type == null ? "STRUCTURED_CONTEXT" : String.valueOf(type);
    }

    private RetrievalState rebuildRetrievalState(RetrievalState current, RecoveryDecision decision) {
        /**
         * 将恢复决策折叠到检索计划中，
         * 让后续检索阶段明确知道哪些通道需要立即刷新。
         */
        List<String> activeQueries = new ArrayList<>();
        if (current.getActiveQueries() != null) {
            activeQueries.addAll(current.getActiveQueries());
        }
        if (decision.needRagRefresh()) {
            activeQueries.add("RECOVERY_REFRESH_RAG");
        }
        if (decision.needMcpRefresh()) {
            activeQueries.add("RECOVERY_REFRESH_MCP");
        }
        Map<String, Object> retrievalPlan = new LinkedHashMap<>();
        if (current.getRetrievalPlan() != null) {
            retrievalPlan.putAll(current.getRetrievalPlan());
        }
        retrievalPlan.put("recovery_mode", true);
        retrievalPlan.put("refresh_rag_now", decision.needRagRefresh());
        retrievalPlan.put("refresh_mcp_now", decision.needMcpRefresh());
        retrievalPlan.put("reassemble_now", decision.needReassembly());
        retrievalPlan.put("refreshRagNow", decision.needRagRefresh());
        retrievalPlan.put("refreshMcpNow", decision.needMcpRefresh());
        retrievalPlan.put("reassembleNow", decision.needReassembly());
        retrievalPlan.put("need_rag_refresh", decision.needRagRefresh());
        retrievalPlan.put("need_mcp_refresh", decision.needMcpRefresh());
        retrievalPlan.put("need_reassembly", decision.needReassembly());
        retrievalPlan.put("invalidated_evidence_refs", decision.invalidatedEvidenceRefs());
        retrievalPlan.put("invalidated_capability_names", decision.invalidatedCapabilityNames());
        retrievalPlan.put("invalidation_reasons_by_ref", decision.invalidationReasonsByRef());

        return RetrievalState.builder()
                .reconstructedIntent(current.getReconstructedIntent())
                .activeQueries(activeQueries.stream().distinct().toList())
                .retrievalPlan(retrievalPlan)
                .selectedEvidenceRefs(current.getSelectedEvidenceRefs())
                .rerankSummary(current.getRerankSummary())
                .build();
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String buildContextDigest(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return "context_missing";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("taskState=").append(contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name());
        sb.append(";relationalState=").append(contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name());
        if (contextPackage.getTaskStateEntity() != null) {
            sb.append(";currentNode=").append(contextPackage.getTaskStateEntity().getCurrentNode());
            sb.append(";pendingQuestions=").append(contextPackage.getTaskStateEntity().getPendingQuestions());
        }
        if (contextPackage.getToolState() != null) {
            sb.append(";lastToolStatus=").append(contextPackage.getToolState().getLastToolStatus());
            sb.append(";lastTool=").append(contextPackage.getToolState().getLastToolName());
        }
        if (contextPackage.getRetrievalState() != null) {
            sb.append(";activeQueries=").append(contextPackage.getRetrievalState().getActiveQueries());
        }
        return sb.toString();
    }

    private String buildSnapshotDigest(ContextSnapshot snapshot) {
        if (snapshot == null) {
            return "snapshot_missing";
        }
        String type = snapshotType(snapshot);
        return "snapshotId=" + safeType(snapshot.getSnapshotId())
                + ";snapshotType=" + type
                + ";planId=" + safeType(snapshot.getPlanId())
                + ";nodeId=" + safeType(snapshot.getNodeId())
                + ";payloadKeys=" + (snapshot.getPayload() == null ? List.of() : snapshot.getPayload().keySet());
    }

    private String resolveSmallAgentModel() {
        if (geminiProperty != null && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null
                && !geminiProperty.getChat().getModelName().isBlank()) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty != null && geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null
                && !geminiProperty.getBig().getModelName().isBlank()) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
    }

    private String stripFence(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            value = value.replaceAll("(?s)```\\s*$", "");
        }
        return value.trim();
    }

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && !word.isBlank() && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> jsonArrayToList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String value = item == null ? "" : item.asText("");
            if (!value.isBlank()) {
                out.add(value);
            }
        });
        return out.stream().distinct().toList();
    }

    private Map<String, String> jsonObjectToStringMap(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue().asText("");
            if (key != null && !key.isBlank() && !value.isBlank()) {
                out.put(key, value);
            }
        });
        return out;
    }

    private List<String> collectEvidenceRefs(StructuredContextPackage contextPackage, ContextSnapshot snapshot) {
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>();
        if (contextPackage != null && contextPackage.getRetrievalState() != null && contextPackage.getRetrievalState().getSelectedEvidenceRefs() != null) {
            refs.addAll(contextPackage.getRetrievalState().getSelectedEvidenceRefs().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .toList());
        }
        if (contextPackage != null && contextPackage.getContextState() != null && contextPackage.getContextState().getActiveKnowledgeRefs() != null) {
            refs.addAll(contextPackage.getContextState().getActiveKnowledgeRefs().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .toList());
        }
        if (contextPackage != null && contextPackage.getContextState() != null && contextPackage.getContextState().getActiveMemoryRefs() != null) {
            refs.addAll(contextPackage.getContextState().getActiveMemoryRefs().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .toList());
        }
        if (contextPackage != null && contextPackage.getContextState() != null && contextPackage.getContextState().getActiveToolEvidenceRefs() != null) {
            refs.addAll(contextPackage.getContextState().getActiveToolEvidenceRefs().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .toList());
        }
        if (snapshot != null && snapshot.getPayload() != null) {
            refs.addAll(readSnapshotRefList(snapshot.getPayload(), "activeKnowledgeRefs"));
            refs.addAll(readSnapshotRefList(snapshot.getPayload(), "activeMemoryRefs"));
            refs.addAll(readSnapshotRefList(snapshot.getPayload(), "activeToolEvidenceRefs"));
        }
        return refs.stream().limit(40).toList();
    }

    private List<String> collectCapabilityNames(StructuredContextPackage contextPackage, ContextSnapshot snapshot) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (contextPackage != null && contextPackage.getContextState() != null) {
            ContextState contextState = contextPackage.getContextState();
            addCapabilityNamesFromRefs(names, contextState.getActiveMcpResourceRefs());
            addCapabilityNamesFromRefs(names, contextState.getActiveMcpPromptRefs());
            addCapabilityNamesFromRefs(names, contextState.getActiveMcpWorkflowRefs());
            addCapabilityNamesFromRefs(names, contextState.getActiveMcpToolRefs());
        }
        if (snapshot != null && snapshot.getPayload() != null) {
            addCapabilityNamesFromRefs(names, readSnapshotRefList(snapshot.getPayload(), "activeMcpResourceRefs"));
            addCapabilityNamesFromRefs(names, readSnapshotRefList(snapshot.getPayload(), "activeMcpPromptRefs"));
            addCapabilityNamesFromRefs(names, readSnapshotRefList(snapshot.getPayload(), "activeMcpWorkflowRefs"));
            addCapabilityNamesFromRefs(names, resolveSnapshotToolRefs(snapshot.getPayload()));
        }
        return names.stream().limit(40).toList();
    }

    private List<String> resolveSnapshotToolRefs(Map<String, Object> payload) {
        List<String> newRefs = readSnapshotRefList(payload, "activeMcpToolRefs");
        if (newRefs != null && !newRefs.isEmpty()) {
            return newRefs;
        }
        List<String> legacyRefs = readSnapshotRefList(payload, "activeMcpResourceRefsLegacy");
        if (legacyRefs != null && !legacyRefs.isEmpty()) {
            return legacyRefs;
        }
        return readSnapshotRefList(payload, "activeMcpResourceRefs");
    }

    @SuppressWarnings("unchecked")
    private List<String> readSnapshotRefList(Map<String, Object> payload, String key) {
        if (payload == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object refsObj = payload.get(key);
        if (refsObj instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
        }
        Object activeRefsObj = payload.get("activeRefs");
        if (activeRefsObj instanceof Map<?, ?> activeRefsMap) {
            Object nested = activeRefsMap.get(key);
            if (nested instanceof List<?> nestedList) {
                return nestedList.stream()
                        .map(item -> item == null ? "" : String.valueOf(item).trim())
                        .filter(item -> !item.isBlank())
                        .distinct()
                        .toList();
            }
        }
        return List.of();
    }

    private void addCapabilityNamesFromRefs(java.util.LinkedHashSet<String> names, List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            String capabilityName = extractCapabilityName(ref);
            if (!capabilityName.isBlank()) {
                names.add(capabilityName);
            }
        }
    }

    private String extractCapabilityName(String ref) {
        String text = ref == null ? "" : ref.trim();
        if (text.isBlank()) {
            return "";
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                var node = objectMapper.readTree(text);
                String capabilityName = node.path("capability_name").asText("");
                if (!capabilityName.isBlank()) {
                    return capabilityName;
                }
                capabilityName = node.path("capabilityName").asText("");
                if (!capabilityName.isBlank()) {
                    return capabilityName;
                }
            } catch (Exception ignore) {
                return "";
            }
        }
        return text;
    }

    private Map<String, String> buildInvalidationReasonMap(List<String> evidenceRefs,
                                                           List<String> capabilityNames,
                                                           String reason) {
        Map<String, String> out = new LinkedHashMap<>();
        if (evidenceRefs != null) {
            for (String ref : evidenceRefs) {
                if (ref != null && !ref.isBlank()) {
                    out.put(ref, reason + ":evidence_stale");
                }
            }
        }
        if (capabilityNames != null) {
            for (String name : capabilityNames) {
                if (name != null && !name.isBlank()) {
                    out.put(name, reason + ":capability_stale");
                }
            }
        }
        return out;
    }

    private record RecoveryDecision(boolean needRagRefresh,
                                    boolean needMcpRefresh,
                                    boolean needReassembly,
                                    String reason,
                                    List<String> invalidatedEvidenceRefs,
                                    List<String> invalidatedCapabilityNames,
                                    Map<String, String> invalidationReasonsByRef) {
    }
}
