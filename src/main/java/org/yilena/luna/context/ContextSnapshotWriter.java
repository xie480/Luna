package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;

import java.util.List;
import java.util.Map;

public interface ContextSnapshotWriter {
    String persistFinalSnapshot(String sessionId,
                                Long planId,
                                Long nodeId,
                                AssembledContext assembledContext,
                                Map<String, Object> rawToolResultChannel,
                                Map<String, List<String>> activeRefs);

    default String persistFinalSnapshot(String sessionId,
                                        Long planId,
                                        Long nodeId,
                                        AssembledContext assembledContext,
                                        Map<String, Object> rawToolResultChannel,
                                        Map<String, List<String>> activeRefs,
                                        Map<String, Object> structuredRecoveryPayload) {
        return persistFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                rawToolResultChannel,
                activeRefs
        );
    }
}
