package org.yilena.luna.context;

import org.yilena.luna.context.model.ContextRerankResult;
import java.util.Map;

public interface RerankTraceLogger {
    void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult);

    default void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult, Map<String, Object> traceMeta) {
        log(sessionId, planId, nodeId, rerankResult);
    }
}
