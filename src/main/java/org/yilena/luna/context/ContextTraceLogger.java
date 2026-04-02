package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;

public interface ContextTraceLogger {
    void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext);
}

