package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MemoryQueryBuilder {

    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        String base = resolveBaseQuery(reconstructionResult);
        String pending = formatList(reconstructionResult == null ? List.of() : reconstructionResult.getMissingSlots());
        String entities = formatEntities(reconstructionResult == null ? Map.of() : reconstructionResult.getClarifiedEntities());
        String constraints = formatList(reconstructionResult == null ? List.of() : reconstructionResult.getBusinessConstraints());
        return base
                + " | memory_focus=true"
                + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name())
                + " | unresolved_slots=" + pending
                + " | clarified_entities=" + entities
                + " | business_constraints=" + constraints;
    }

    private String resolveBaseQuery(InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null) {
            return "memory_retrieval_missing_reconstruction";
        }
        List<String> candidates = List.of(
                safe(reconstructionResult.getExplicitTaskGoal()),
                safe(reconstructionResult.getNormalizedUserIntent()),
                safe(reconstructionResult.getReformulatedQueryForRag())
        );
        for (String candidate : candidates) {
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "memory_retrieval_missing_reconstruction";
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

