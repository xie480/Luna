package org.yilena.luna.memory;

import org.yilena.luna.memory.model.StructuredContextPackage;

public interface RuntimeAuditService {
    void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage);

    void persistDecisionRecord(String sessionId, String decisionType, String decisionReason, String decisionPayloadJson);

    void persistToolExecutionTrace(String sessionId,
                                   String toolName,
                                   String callStatus,
                                   String normalizedInputJson,
                                   String normalizedOutputJson,
                                   String errorMessage,
                                   Long latencyMs);
}
