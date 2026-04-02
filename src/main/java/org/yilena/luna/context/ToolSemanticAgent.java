package org.yilena.luna.context;

import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.TaskRuntimeState;

public interface ToolSemanticAgent {
    ToolSemanticResult translate(String toolName,
                                 String toolDescription,
                                 String rawResult,
                                 TaskRuntimeState taskState,
                                 String currentNodeGoal);
}
