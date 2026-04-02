package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RagQueryBuilder {

    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState, String fallbackRawInput) {
        if (reconstructionResult == null) {
            return fallbackRawInput == null ? "" : fallbackRawInput;
        }
        String base = reconstructionResult.getReformulatedQueryForRag();
        if (base == null || base.isBlank()) {
            base = reconstructionResult.getNormalizedUserIntent();
        }
        if (base == null || base.isBlank()) {
            base = fallbackRawInput == null ? "" : fallbackRawInput;
        }
        String entities = formatEntities(reconstructionResult.getClarifiedEntities());
        String constraints = formatList(reconstructionResult.getBusinessConstraints());
        String timeScope = safe(reconstructionResult.getTimeScope());
        String blueprintHint = safe(reconstructionResult.getBlueprintHint());
        return base
                + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name())
                + " | clarified_entities=" + entities
                + " | business_constraints=" + constraints
                + " | time_scope=" + timeScope
                + " | blueprint_hint=" + blueprintHint;
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
