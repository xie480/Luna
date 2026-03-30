package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

public interface ContextCompilerService {
    StructuredContextPackage compile(String sessionId,
                                     String userInput,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState);
}
