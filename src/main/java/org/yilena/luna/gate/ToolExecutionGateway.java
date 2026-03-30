package org.yilena.luna.gate; // define package

import org.yilena.luna.entity.ExecutionResult; // import dependency
import org.yilena.luna.entity.Resource; // import dependency

public interface ToolExecutionGateway { // define interface

    ExecutionResult executeTool(String sessionId, Resource resource, String argsJson); // business logic
} // block end
