package org.yilena.luna.state.store;

import org.yilena.luna.context.model.AssembledContext;

import java.util.List;
import java.util.Map;

public interface ContextSnapshotStore {
    void savePreToolDecisionSnapshot(String sessionId,
                                     Long planId,
                                     Long nodeId,
                                     String userInput,
                                     String reconstructedMcpQuery,
                                     List<Map<String, Object>> executionCandidates,
                                     Map<String, Object> extra);

    void saveFinalSnapshot(String sessionId,
                           Long planId,
                           Long nodeId,
                           AssembledContext assembledContext,
                           String prompt,
                           Map<String, Integer> sectionTokenCounts,
                           Map<String, Double> sectionTokenRatios);
}
