package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultSummaryAgent implements SummaryAgent {

    @Override
    public SummaryResult summarize(String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        String narrative = buildNarrative(userInput, assistantReply, contextPackage);
        Map<String, Object> snapshot = buildStateSnapshot(contextPackage);
        return SummaryResult.builder()
                .narrativeSummary(narrative)
                .stateSnapshot(snapshot)
                .build();
    }

    private String buildNarrative(String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("User intent: ").append(safe(userInput)).append(". ");
        if (contextPackage != null) {
            sb.append("Task state=").append(contextPackage.getTaskState()).append(", relational state=")
                    .append(contextPackage.getRelationalState()).append(". ");
            List<Map<String, Object>> recent = contextPackage.getRecentMessages();
            if (recent != null && !recent.isEmpty()) {
                Map<String, Object> latest = recent.get(recent.size() - 1);
                sb.append("Recent context includes latest role=").append(safe(latest.get("role"))).append(". ");
            }
        }
        sb.append("Assistant response delivered: ").append(safe(assistantReply));
        return sb.toString().trim();
    }

    private Map<String, Object> buildStateSnapshot(StructuredContextPackage contextPackage) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (contextPackage == null) {
            snapshot.put("taskState", "UNKNOWN");
            snapshot.put("relationalState", "UNKNOWN");
            snapshot.put("nextStep", "continue");
            return snapshot;
        }
        snapshot.put("taskState", contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name());
        snapshot.put("relationalState", contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name());
        snapshot.put("tokenBudgetPlan", contextPackage.getTokenBudgetPlan() == null ? Map.of() : contextPackage.getTokenBudgetPlan());
        snapshot.put("activeCapabilities", contextPackage.getCapabilityCandidates() == null ? 0 : contextPackage.getCapabilityCandidates().size());
        snapshot.put("nextStep", inferNextStep(contextPackage));
        return snapshot;
    }

    private String inferNextStep(StructuredContextPackage contextPackage) {
        if (contextPackage.getTaskState() == null) {
            return "continue";
        }
        return switch (contextPackage.getTaskState()) {
            case PLANNING, REPLANNING -> "build_or_update_plan";
            case EXECUTING -> "execute_or_call_tool";
            case WAITING_APPROVAL -> "wait_approval";
            case WAITING_TOOL -> "wait_tool_result";
            case REPORTING -> "finalize_report";
            default -> "continue_dialog";
        };
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

