package org.yilena.luna.memory;

import org.yilena.luna.memory.model.OrchestrationDecision;

import java.util.Map;

public interface EventIngressService {

    OrchestrationDecision ingestUserInput(String sessionId, String userInput);

    default OrchestrationDecision ingestUserInput(String sessionId, String userInput, String orchestrationSignal) {
        return ingestUserInput(sessionId, userInput);
    }

    OrchestrationDecision ingestToolResult(String sessionId, Map<String, Object> payload);

    OrchestrationDecision ingestApproval(String sessionId, Map<String, Object> payload);

    OrchestrationDecision ingestSystemEvent(String sessionId, String eventType, Map<String, Object> payload);

    void dispatchPendingEvents(int limit);
}
