package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class McpQueryBuilder {

    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        String base = resolveBaseQuery(reconstructionResult);
        String entities = formatEntities(reconstructionResult == null ? Map.of() : reconstructionResult.getClarifiedEntities());
        String constraints = formatList(reconstructionResult == null ? List.of() : reconstructionResult.getBusinessConstraints());
        String timeScope = safe(reconstructionResult == null ? "" : reconstructionResult.getTimeScope());
        String blueprintHint = safe(reconstructionResult == null ? "" : reconstructionResult.getBlueprintHint());
        String explicitGoal = safe(reconstructionResult == null ? "" : reconstructionResult.getExplicitTaskGoal());
        return base
                + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name())
                + " | explicit_task_goal=" + explicitGoal
                + " | clarified_entities=" + entities
                + " | business_constraints=" + constraints
                + " | time_scope=" + timeScope
                + " | blueprint_hint=" + blueprintHint;
    }

    private String resolveBaseQuery(InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null) {
            return "reconstruction_missing_for_mcp";
        }
        List<String> candidates = List.of(
                safe(reconstructionResult.getReformulatedQueryForMcp()),
                safe(reconstructionResult.getExplicitTaskGoal()),
                safe(reconstructionResult.getNormalizedUserIntent()),
                safe(reconstructionResult.getBlueprintHint())
        );
        for (String candidate : candidates) {
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "reconstruction_missing_for_mcp";
    }

    private String formatEntities(Map<String, String> entities) {
        if (entities == null || entities.isEmpty()) {
            return "[]";
        }
        return entities.entrySet().stream()
                .map(entry -> safe(entry.getKey()) + "=" + safe(entry.getValue()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(this::safe)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
