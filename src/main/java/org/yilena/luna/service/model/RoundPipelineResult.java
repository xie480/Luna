package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;

@Value
@Builder
public class RoundPipelineResult {
    boolean blocked;
    String blockedReason;
    ToolSemanticResult toolSemanticResult;
    SummaryResult preAssemblySummary;
    MainModelOrchestrationResult mainModelResult;
    SummaryResult summaryResult;
    String finalSnapshotId;
}

