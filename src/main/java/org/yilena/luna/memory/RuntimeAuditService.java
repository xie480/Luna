package org.yilena.luna.memory;

import org.yilena.luna.memory.model.StructuredContextPackage;

public interface RuntimeAuditService {
    void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage);

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
