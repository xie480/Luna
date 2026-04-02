package org.yilena.luna.context;

import org.yilena.luna.context.model.ContextRerankResult;

public interface RerankTraceLogger {
    void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult);
}

