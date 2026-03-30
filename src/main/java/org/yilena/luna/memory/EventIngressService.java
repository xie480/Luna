package org.yilena.luna.memory;

import org.yilena.luna.memory.model.OrchestrationDecision;

public interface EventIngressService {

    OrchestrationDecision ingestUserInput(String sessionId, String userInput);

    void dispatchPendingEvents(int limit);
}

