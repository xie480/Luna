package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SummaryStateSnapshotValidator {

    public ValidationResult validate(SummaryResult summaryResult,
                                     StructuredContextPackage contextPackage,
                                     ToolSemanticResult latestToolSemanticResult) {
        if (summaryResult == null) {
            Map<String, Object> fallback = fallbackSnapshot(contextPackage, latestToolSemanticResult);
            SummaryResult normalized = SummaryResult.builder()
                    .narrativeSummary("")
                    .stateSnapshot(fallback)
                    .build();
            return new ValidationResult(false, List.of("summary_result_missing"), normalized);
        }

        Map<String, Object> raw = summaryResult.getStateSnapshot() == null ? Map.of() : summaryResult.getStateSnapshot();
        List<String> issues = new ArrayList<>();
        Map<String, Object> normalized = new LinkedHashMap<>();

        TaskState taskState = contextPackage == null ? null : contextPackage.getTaskStateEntity();
        ToolState toolState = contextPackage == null ? null : contextPackage.getToolState();
        String runtimeStage = contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name();

        String currentStage = text(raw.get("currentStage"));
        if (currentStage.isBlank()) {
            issues.add("state_snapshot_missing_current_stage");
            currentStage = runtimeStage.isBlank() ? "UNKNOWN" : runtimeStage;
        } else if (!runtimeStage.isBlank() && !runtimeStage.equalsIgnoreCase(currentStage)) {
            issues.add("state_snapshot_conflict_current_stage");
            currentStage = runtimeStage;
        }
        normalized.put("currentStage", currentStage);

        Map<String, Object> confirmedSlots = map(raw.get("confirmedSlots"));
        if (confirmedSlots.isEmpty() && taskState != null && taskState.getConfirmedSlots() != null) {
            confirmedSlots = taskState.getConfirmedSlots();
        }
        normalized.put("confirmedSlots", confirmedSlots);

        List<String> finishedSteps = list(raw.get("finishedSteps"));
        if (finishedSteps.isEmpty() && taskState != null && taskState.getFinishedSteps() != null) {
            finishedSteps = taskState.getFinishedSteps();
        }
        normalized.put("finishedSteps", finishedSteps);

        List<String> pendingIssues = list(raw.get("pendingIssues"));
        if (pendingIssues.isEmpty()) {
            pendingIssues = list(raw.get("pendingQuestions"));
        }
        List<String> requiredPending = taskState == null || taskState.getPendingQuestions() == null ? List.of() : taskState.getPendingQuestions();
        if (!requiredPending.isEmpty()) {
            List<String> merged = new ArrayList<>(pendingIssues);
            for (String item : requiredPending) {
                if (item != null && !item.isBlank() && !merged.contains(item)) {
                    merged.add(item);
                    issues.add("state_snapshot_pending_issue_missing");
                }
            }
            pendingIssues = merged;
        }
        normalized.put("pendingIssues", pendingIssues);

        String latestToolConclusion = text(raw.get("latestToolConclusion"));
        if (latestToolConclusion.isBlank()) {
            latestToolConclusion = latestToolSemanticResult == null ? "" : text(latestToolSemanticResult.getBusinessImpact());
        }
        if (latestToolConclusion.isBlank()) {
            latestToolConclusion = toolState == null ? "" : text(toolState.getLastToolSemanticSummary());
        }
        if (latestToolConclusion.isBlank() && latestToolSemanticResult != null
                && latestToolSemanticResult.getToolStatus() != null
                && !"UNKNOWN".equalsIgnoreCase(latestToolSemanticResult.getToolStatus())) {
            issues.add("state_snapshot_missing_tool_conclusion");
        }
        normalized.put("latestToolConclusion", latestToolConclusion);

        List<String> currentConstraints = list(raw.get("currentConstraints"));
        if (currentConstraints.isEmpty()) {
            currentConstraints = list(raw.get("constraints"));
        }
        normalized.put("currentConstraints", currentConstraints);

        String nextStep = text(raw.get("nextStep"));
        if (nextStep.isBlank()) {
            issues.add("state_snapshot_missing_next_step");
            nextStep = inferNextStep(contextPackage);
        }
        normalized.put("nextStep", nextStep);

        SummaryResult normalizedResult = SummaryResult.builder()
                .narrativeSummary(summaryResult.getNarrativeSummary() == null ? "" : summaryResult.getNarrativeSummary())
                .stateSnapshot(normalized)
                .build();
        return new ValidationResult(issues.isEmpty(), issues, normalizedResult);
    }

    private Map<String, Object> fallbackSnapshot(StructuredContextPackage contextPackage, ToolSemanticResult latestToolSemanticResult) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("currentStage", contextPackage == null || contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name());
        fallback.put("confirmedSlots", contextPackage == null || contextPackage.getTaskStateEntity() == null || contextPackage.getTaskStateEntity().getConfirmedSlots() == null
                ? Map.of()
                : contextPackage.getTaskStateEntity().getConfirmedSlots());
        fallback.put("finishedSteps", contextPackage == null || contextPackage.getTaskStateEntity() == null || contextPackage.getTaskStateEntity().getFinishedSteps() == null
                ? List.of()
                : contextPackage.getTaskStateEntity().getFinishedSteps());
        fallback.put("pendingIssues", contextPackage == null || contextPackage.getTaskStateEntity() == null || contextPackage.getTaskStateEntity().getPendingQuestions() == null
                ? List.of()
                : contextPackage.getTaskStateEntity().getPendingQuestions());
        fallback.put("latestToolConclusion", latestToolSemanticResult == null ? "" : text(latestToolSemanticResult.getBusinessImpact()));
        fallback.put("currentConstraints", List.of());
        fallback.put("nextStep", inferNextStep(contextPackage));
        return fallback;
    }

    private String inferNextStep(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskState() == null) {
            return "continue_dialog";
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private List<String> list(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = text(item);
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out.stream().distinct().toList();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ValidationResult(boolean valid, List<String> issues, SummaryResult normalized) {
    }
}

