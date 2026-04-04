package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.AssembledContext;

@Value
@Builder
public class MainModelOrchestrationResult {
    boolean blocked;
    String blockedReason;
    AssembledContext assembledContext;
    String finalSnapshotId;
    String finalPrompt;
    String rawResponse;
    String validResponse;
    String replyText;
}

