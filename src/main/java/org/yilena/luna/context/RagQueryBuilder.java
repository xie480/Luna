package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

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
        return base + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name());
    }
}

