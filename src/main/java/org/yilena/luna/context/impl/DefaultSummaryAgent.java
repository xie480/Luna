package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        StringBuilder sb = new StringBuilder(320);
        sb.append("User intent: ").append(safe(userInput)).append(". ");
        if (contextPackage != null) {
            sb.append("Task state=").append(contextPackage.getTaskState()).append(", relational state=")
                    .append(contextPackage.getRelationalState()).append(". ");
            List<Map<String, Object>> recent = contextPackage.getRecentMessages();
            if (recent != null && !recent.isEmpty()) {
                int total = recent.size();
                int from = Math.max(0, total - 20);
                Map<String, Long> roleCounts = recent.subList(from, total).stream()
                        .collect(Collectors.groupingBy(row -> safe(row.get("role")), LinkedHashMap::new, Collectors.counting()));
                sb.append("Short-term memory size=").append(total).append(", recent role distribution=").append(roleCounts).append(". ");
                sb.append("Latest interactions: ");
                recent.subList(from, total).forEach(row ->
                        sb.append("[").append(safe(row.get("role"))).append("] ").append(safe(row.get("content_text"))).append(" | "));
            }
            if (contextPackage.getTaskStateEntity() != null) {
                sb.append("Task objective=").append(safe(contextPackage.getTaskStateEntity().getObjective())).append("; ");
                sb.append("Pending questions=").append(safe(contextPackage.getTaskStateEntity().getPendingQuestions())).append("; ");
            }
            if (contextPackage.getToolState() != null) {
                sb.append("Latest tool=").append(safe(contextPackage.getToolState().getLastToolName()))
                        .append(", status=").append(safe(contextPackage.getToolState().getLastToolStatus())).append(". ");
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
        snapshot.put("shortTermMemorySize", contextPackage.getRecentMessages() == null ? 0 : contextPackage.getRecentMessages().size());
        snapshot.put("tokenBudgetPlan", contextPackage.getTokenBudgetPlan() == null ? Map.of() : contextPackage.getTokenBudgetPlan());
        snapshot.put("activeCapabilities", contextPackage.getCapabilityCandidates() == null ? 0 : contextPackage.getCapabilityCandidates().size());
        snapshot.put("taskStateEntity", contextPackage.getTaskStateEntity() == null ? Map.of() : contextPackage.getTaskStateEntity());
        snapshot.put("retrievalState", contextPackage.getRetrievalState() == null ? Map.of() : contextPackage.getRetrievalState());
        snapshot.put("toolState", contextPackage.getToolState() == null ? Map.of() : contextPackage.getToolState());
        snapshot.put("contextState", contextPackage.getContextState() == null ? Map.of() : contextPackage.getContextState());
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
