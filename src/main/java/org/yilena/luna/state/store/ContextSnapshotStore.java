package org.yilena.luna.state.store;

import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.state.model.ContextSnapshot;

import java.util.List;
import java.util.Map;

public interface ContextSnapshotStore {
    String savePreToolDecisionSnapshot(String sessionId,
                                       Long planId,
                                       Long nodeId,
                                       String userInput,
                                       String reconstructedMcpQuery,
                                       List<Map<String, Object>> executionCandidates,
                                       Map<String, Object> extra);

    default String savePreToolDecisionSnapshot(String sessionId,
                                               Long planId,
                                               Long nodeId,
                                               String userInput,
                                               String reconstructedMcpQuery,
                                               List<Map<String, Object>> executionCandidates,
                                               Map<String, Object> extra,
                                               Map<String, Object> rawToolResultChannel) {
        return savePreToolDecisionSnapshot(
                sessionId,
                planId,
                nodeId,
                userInput,
                reconstructedMcpQuery,
                executionCandidates,
                extra
        );
    }

    default String saveToolDecisionContextSnapshot(String sessionId,
                                                   Long planId,
                                                   Long nodeId,
                                                   String assembledDecisionContext,
                                                   Map<String, List<String>> sections,
                                                   List<Map<String, Object>> executionCandidates,
                                                   Map<String, Integer> sectionTokenCounts,
                                                   Map<String, Double> sectionTokenRatios,
                                                   Map<String, Object> extra) {
        return savePreToolDecisionSnapshot(
                sessionId,
                planId,
                nodeId,
                "",
                assembledDecisionContext,
                executionCandidates,
                extra
        );
    }

    String saveFinalSnapshot(String sessionId,
                             Long planId,
                             Long nodeId,
                             AssembledContext assembledContext,
                             String prompt,
                             Map<String, Integer> sectionTokenCounts,
                             Map<String, Double> sectionTokenRatios);

    default String saveFinalSnapshot(String sessionId,
                                     Long planId,
                                     Long nodeId,
                                     AssembledContext assembledContext,
                                     String prompt,
                                     Map<String, Integer> sectionTokenCounts,
                                     Map<String, Double> sectionTokenRatios,
                                     Map<String, Object> rawToolResultChannel) {
        return saveFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                rawToolResultChannel,
                Map.of()
        );
    }

    default String saveFinalSnapshot(String sessionId,
                                     Long planId,
                                     Long nodeId,
                                     AssembledContext assembledContext,
                                     String prompt,
                                     Map<String, Integer> sectionTokenCounts,
                                     Map<String, Double> sectionTokenRatios,
                                     Map<String, Object> rawToolResultChannel,
                                     Map<String, List<String>> activeRefs) {
        return saveFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                rawToolResultChannel,
                activeRefs,
                Map.of()
        );
    }

    default String saveFinalSnapshot(String sessionId,
                                     Long planId,
                                     Long nodeId,
                                     AssembledContext assembledContext,
                                     String prompt,
                                     Map<String, Integer> sectionTokenCounts,
                                     Map<String, Double> sectionTokenRatios,
                                     Map<String, Object> rawToolResultChannel,
                                     Map<String, List<String>> activeRefs,
                                     Map<String, Object> structuredRecoveryPayload) {
        return saveFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios
        );
    }

    ContextSnapshot load(String sessionId, String snapshotId);

    ContextSnapshot loadLatest(String sessionId);
}
