package org.yilena.luna.service;

import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.model.NodeWorksetResult;
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

    NodeWorksetResult orchestrateNodeWorkset(String sessionId,
                                             String userInput,
                                             OrchestrationDecision decision,
                                             StructuredContextPackage contextPackage,
                                             InputReconstructionResult reconstructionResult);
}
