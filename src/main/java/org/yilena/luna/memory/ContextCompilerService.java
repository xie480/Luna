package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.ContextCompileOptions;
import org.yilena.luna.memory.model.StructuredContextPackage;

public interface ContextCompilerService {
    default StructuredContextPackage compile(String sessionId,
                                             String userInput,
                                             TaskRuntimeState taskState,
                                             RelationalRuntimeState relationalState) {
        return compile(sessionId, userInput, taskState, relationalState, ContextCompileOptions.auto());
    }

    StructuredContextPackage compile(String sessionId,
                                     String userInput,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState,
                                     ContextCompileOptions options);
}
