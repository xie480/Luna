package org.yilena.luna.memory;

import org.yilena.luna.memory.model.OrchestrationDecision;

public interface SessionOrchestratorService {
    OrchestrationDecision onUserInput(String sessionId, String userInput);
}
