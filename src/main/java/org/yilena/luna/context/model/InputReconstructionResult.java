package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class InputReconstructionResult {
    String normalizedUserIntent;
    String explicitTaskGoal;
    Map<String, String> clarifiedEntities;
    List<String> missingSlots;
    String timeScope;
    List<String> businessConstraints;
    String reformulatedQueryForRag;
    String reformulatedQueryForMcp;
    String blueprintHint;
    double intentConfidence;
}

