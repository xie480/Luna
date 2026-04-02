package org.yilena.luna.service;

import org.yilena.luna.service.model.TaskOrchestrationResult;

import java.util.Map;

public interface TaskOrchestratorService {

    TaskOrchestrationResult orchestrateUserInput(String sessionId, String userInput);

    TaskOrchestrationResult orchestrateSystemRecovery(String sessionId,
                                                      String userInput,
                                                      String eventType,
                                                      Map<String, Object> eventPayload,
                                                      String recoveryEvent,
                                                      String interruptReason);
}
