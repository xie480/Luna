package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;
import java.util.Map;

public interface ContextTraceLogger {
    void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext);

    default void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext, Map<String, Object> traceMeta) {
        log(sessionId, planId, nodeId, assembledContext);
    }
}
