package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

@Value
@Builder
public class BlueprintOrchestrationResult {
    StructuredContextPackage contextPackage;
    InputReconstructionResult reconstructionResult;
    OrchestrationDecision decision;
    NodeWorksetResult nodeWorksetResult;
    BlueprintDraft blueprintDraft;
}
