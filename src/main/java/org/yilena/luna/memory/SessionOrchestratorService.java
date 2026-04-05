package org.yilena.luna.memory;

import org.yilena.luna.memory.model.OrchestrationDecision;

public interface SessionOrchestratorService {
    OrchestrationDecision onUserInput(String sessionId, String userInput);

    OrchestrationDecision onUserInput(String sessionId, String userInput, String orchestrationSignal);

    OrchestrationDecision onToolResult(String sessionId, String payloadJson);

    OrchestrationDecision onApproval(String sessionId, String payloadJson);

    OrchestrationDecision onSystemEvent(String sessionId, String eventType, String payloadJson);
}
