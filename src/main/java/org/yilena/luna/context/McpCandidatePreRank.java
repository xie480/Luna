package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class McpCandidatePreRank {

    public List<Map<String, Object>> preRank(String mcpQuery,
                                             List<Map<String, Object>> candidates,
                                             InputReconstructionResult reconstructionResult,
                                             TaskRuntimeState taskState,
                                             int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String terms = buildTerms(mcpQuery, reconstructionResult, taskState);
        return candidates.stream()
                .map(this::copy)
                .sorted(Comparator.comparingDouble((Map<String, Object> row) -> score(row, terms, taskState)).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private double score(Map<String, Object> row, String terms, TaskRuntimeState taskState) {
        String capabilityType = safe(row.get("capability_type")).toUpperCase(Locale.ROOT);
        String capabilityName = safe(row.get("capability_name")).toLowerCase(Locale.ROOT);
        String description = safe(row.get("description")).toLowerCase(Locale.ROOT);
        String text = capabilityName + " " + description;

        double score = 1.0;
        score += overlapScore(text, terms);
        score -= riskPenalty(row);

        if ((taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL)
                && "TOOL".equals(capabilityType)) {
            score += 0.35;
        }
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING)
                && "WORKFLOW".equals(capabilityType)) {
            score += 0.25;
        }
        if ("PROMPT".equals(capabilityType) || "RESOURCE".equals(capabilityType)) {
            score += 0.08;
        }
        return score;
    }

    private double overlapScore(String text, String terms) {
        if (text.isBlank() || terms.isBlank()) {
            return 0.0;
        }
        List<String> parts = new ArrayList<>();
        for (String one : terms.split("[\\s,;|]+")) {
            if (one != null && !one.isBlank() && one.length() >= 2) {
                parts.add(one.toLowerCase(Locale.ROOT));
            }
        }
        if (parts.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (String one : parts) {
            if (text.contains(one)) {
                hits++;
            }
        }
        return Math.min(0.8, hits * 0.08);
    }

    private double riskPenalty(Map<String, Object> row) {
        String sensitivity = safe(row.get("sensitivity")).toUpperCase(Locale.ROOT);
        boolean requiresApproval = boolVal(row.get("requires_approval"));
        double penalty = switch (sensitivity) {
            case "HIGH" -> 0.45;
            case "MEDIUM" -> 0.25;
            default -> 0.05;
        };
        if (requiresApproval) {
            penalty += 0.20;
        }
        return penalty;
    }

    private String buildTerms(String mcpQuery, InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        StringBuilder terms = new StringBuilder();
        if (mcpQuery != null) {
            terms.append(mcpQuery).append(' ');
        }
        if (reconstructionResult != null) {
            terms.append(safe(reconstructionResult.getExplicitTaskGoal())).append(' ');
            terms.append(safe(reconstructionResult.getNormalizedUserIntent())).append(' ');
            if (reconstructionResult.getClarifiedEntities() != null) {
                terms.append(reconstructionResult.getClarifiedEntities().values());
            }
            terms.append(' ');
            if (reconstructionResult.getBusinessConstraints() != null) {
                terms.append(reconstructionResult.getBusinessConstraints());
            }
        }
        terms.append(' ').append(taskState == null ? "UNKNOWN" : taskState.name());
        return terms.toString().trim();
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean boolVal(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }
}
