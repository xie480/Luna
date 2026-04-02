package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;

public interface SummaryTraceLogger {
    void log(String sessionId, Long planId, Long nodeId, SummaryResult summaryResult);
}

