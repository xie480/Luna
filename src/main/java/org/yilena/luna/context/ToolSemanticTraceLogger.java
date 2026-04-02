package org.yilena.luna.context;

import org.yilena.luna.context.model.ToolSemanticResult;

public interface ToolSemanticTraceLogger {
    void log(String sessionId, Long planId, Long nodeId, ToolSemanticResult semanticResult);
}

