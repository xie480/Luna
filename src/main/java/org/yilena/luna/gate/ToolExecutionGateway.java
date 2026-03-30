package org.yilena.luna.gate;

import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;

public interface ToolExecutionGateway {

    ExecutionResult executeTool(String sessionId, Resource resource, String argsJson);
}
