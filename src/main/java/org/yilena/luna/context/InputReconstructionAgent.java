package org.yilena.luna.context;

import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

public interface InputReconstructionAgent {
    InputReconstructionResult reconstruct(String sessionId,
                                          String userInput,
                                          StructuredContextPackage contextPackage,
                                          TaskRuntimeState taskState,
                                          RelationalRuntimeState relationalState);
}

