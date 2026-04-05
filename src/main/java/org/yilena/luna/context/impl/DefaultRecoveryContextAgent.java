package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.RecoveryContextAgent;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.ContextSnapshot;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
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

    public DefaultRecoveryContextAgent(RecoveryStateStore recoveryStateStore,
                                       ContextSnapshotStore contextSnapshotStore,
                                       ObjectMapper objectMapper,
                                       LlmClientUtil llmClientUtil,
                                       GeminiProperty geminiProperty) {
        this.recoveryStateStore = recoveryStateStore;
        this.contextSnapshotStore = contextSnapshotStore;
        this.objectMapper = objectMapper;
        this.llmClientUtil = llmClientUtil;
        this.geminiProperty = geminiProperty;
    }

    @Override
    public StructuredContextPackage recover(String sessionId,
                                            StructuredContextPackage contextPackage,
                                            String recoveryEvent,
                                            String interruptReason) {
        if (sessionId == null || sessionId.isBlank()) {
            return contextPackage;
        }
        String requestedSnapshotId = resolveRecoverySnapshotId(contextPackage);
        ContextSnapshot snapshot = loadSnapshot(sessionId, requestedSnapshotId);
        StructuredContextPackage restoredContext = rebuildFromSnapshot(contextPackage, snapshot);
        RecoveryDecision decision = evaluateRecoveryDecision(recoveryEvent, interruptReason, restoredContext, snapshot);
        String resolvedSnapshotId = resolveSnapshotId(snapshot, requestedSnapshotId, sessionId);
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
            String prompt = RECOVERY_DECISION_PROMPT.formatted(
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
        String event = normalize(recoveryEvent);
        String reason = normalize(interruptReason);
        boolean staleByTimeout = containsAny(reason, "timeout", "expired", "过期", "超时");
        boolean staleByDataMutation = containsAny(event, "TOOL_RESULT", "APPROVAL", "SYSTEM", "TIMER") || containsAny(reason, "schema", "validation", "变更", "冲突");
        boolean staleByFailure = containsAny(reason, "failed", "error", "失败", "异常");
        boolean needRagRefresh = staleByTimeout || staleByDataMutation;
        boolean needMcpRefresh = staleByFailure || staleByDataMutation;
        boolean needReassembly = needRagRefresh || needMcpRefresh || contextPackage == null || snapshot == null;
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
        return null;
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

    private String snapshotType(ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.getPayload() == null) {
            return "UNKNOWN";
        }
        Object type = snapshot.getPayload().get("snapshotType");
        return type == null ? "STRUCTURED_CONTEXT" : String.valueOf(type);
    }

    private RetrievalState rebuildRetrievalState(RetrievalState current, RecoveryDecision decision) {
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
        }
        if (snapshot != null && snapshot.getPayload() != null) {
            addCapabilityNamesFromRefs(names, readSnapshotRefList(snapshot.getPayload(), "activeMcpResourceRefs"));
            addCapabilityNamesFromRefs(names, readSnapshotRefList(snapshot.getPayload(), "activeMcpPromptRefs"));
        }
        return names.stream().limit(40).toList();
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
