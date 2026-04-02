package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.RecoveryContextAgent;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.store.RecoveryStateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultRecoveryContextAgent implements RecoveryContextAgent {

    private final RecoveryStateStore recoveryStateStore;

    public DefaultRecoveryContextAgent(RecoveryStateStore recoveryStateStore) {
        this.recoveryStateStore = recoveryStateStore;
    }

    @Override
    public StructuredContextPackage recover(String sessionId,
                                            StructuredContextPackage contextPackage,
                                            String recoveryEvent,
                                            String interruptReason) {
        if (sessionId == null || sessionId.isBlank()) {
            return contextPackage;
        }
        RecoveryDecision decision = evaluateRecoveryDecision(recoveryEvent, interruptReason, contextPackage);
        RecoveryState state = RecoveryState.builder()
                .interruptedAt(Instant.now().toString())
                .interruptReason(interruptReason == null ? "" : interruptReason)
                .recoveryEvent(recoveryEvent == null ? "UNKNOWN_RECOVERY" : recoveryEvent)
                .recoverySnapshotId(sessionId + ":" + System.currentTimeMillis())
                .build();
        recoveryStateStore.save(sessionId, state);
        if (contextPackage == null) {
            return null;
        }
        contextPackage.setPromptPolicy(mergePromptPolicy(contextPackage.getPromptPolicy(), decision));
        if (contextPackage.getRetrievalState() != null) {
            contextPackage.setRetrievalState(rebuildRetrievalState(contextPackage.getRetrievalState(), decision));
        }
        contextPackage.setRecoveryState(state);
        return contextPackage;
    }

    private RecoveryDecision evaluateRecoveryDecision(String recoveryEvent, String interruptReason, StructuredContextPackage contextPackage) {
        String event = normalize(recoveryEvent);
        String reason = normalize(interruptReason);
        boolean staleByTimeout = containsAny(reason, "timeout", "expired", "过期", "超时");
        boolean staleByDataMutation = containsAny(event, "TOOL_RESULT", "APPROVAL", "SYSTEM", "TIMER") || containsAny(reason, "schema", "validation", "变更", "冲突");
        boolean staleByFailure = containsAny(reason, "failed", "error", "失败", "异常");
        boolean needRagRefresh = staleByTimeout || staleByDataMutation;
        boolean needMcpRefresh = staleByFailure || staleByDataMutation;
        boolean needReassembly = needRagRefresh || needMcpRefresh || contextPackage == null;
        return new RecoveryDecision(needRagRefresh, needMcpRefresh, needReassembly, reason);
    }

    private Map<String, Object> mergePromptPolicy(Map<String, Object> current, RecoveryDecision decision) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        merged.put("recovery_required", decision.needReassembly());
        merged.put("recovery_need_rag_refresh", decision.needRagRefresh());
        merged.put("recovery_need_mcp_refresh", decision.needMcpRefresh());
        merged.put("recovery_reason", decision.reason());
        return merged;
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
        retrievalPlan.put("need_rag_refresh", decision.needRagRefresh());
        retrievalPlan.put("need_mcp_refresh", decision.needMcpRefresh());

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

    private record RecoveryDecision(boolean needRagRefresh, boolean needMcpRefresh, boolean needReassembly, String reason) {
    }
}
