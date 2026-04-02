package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;

public interface SummaryTraceLogger {
    void log(String sessionId,
             Long planId,
             Long nodeId,
             String userInput,
             String assistantReply,
             StructuredContextPackage contextPackage,
             SummaryResult summaryResult,
             String triggerSource);
}
