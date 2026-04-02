package org.yilena.luna.memory;

import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Map;

public interface RuntimeAuditService {
    void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage);

    void persistFinalContextSnapshot(String sessionId,
                                     Long planId,
                                     Long nodeId,
                                     AssembledContext assembledContext,
                                     String prompt,
                                     Map<String, Integer> sectionTokenCounts,
                                     Map<String, Double> sectionTokenRatios);

    void persistDecisionRecord(String sessionId,
                               Long planId,
                               Long nodeId,
                               String decisionType,
                               String decisionReason,
                               String decisionPayloadJson);

    void persistToolExecutionTrace(String sessionId,
                                   Long planId,
                                   Long nodeId,
                                   String toolName,
                                   String callStatus,
                                   String normalizedInputJson,
                                   String normalizedOutputJson,
                                   String errorMessage,
                                   Long latencyMs);
}
