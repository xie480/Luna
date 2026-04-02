package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class RetrievalState {
    String reconstructedIntent;
    List<String> activeQueries;
    Map<String, Object> retrievalPlan;
    List<String> selectedEvidenceRefs;
    String rerankSummary;
}

