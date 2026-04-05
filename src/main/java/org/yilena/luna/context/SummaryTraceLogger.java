package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;
import java.util.Map;

public interface SummaryTraceLogger {
    void log(String sessionId,
             Long planId,
             Long nodeId,
             String userInput,
             String assistantReply,
             StructuredContextPackage contextPackage,
             SummaryResult summaryResult,
             String triggerSource);

    default void log(String sessionId,
                     Long planId,
                     Long nodeId,
                     String userInput,
                     String assistantReply,
                     StructuredContextPackage contextPackage,
                     SummaryResult summaryResult,
                     String triggerSource,
                     Map<String, Object> traceMeta) {
        log(sessionId, planId, nodeId, userInput, assistantReply, contextPackage, summaryResult, triggerSource);
    }
}
