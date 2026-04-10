package org.yilena.luna.context;

import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;
import java.util.Map;

/**
 * 摘要追踪日志接口，负责记录摘要生成时的输入上下文和输出结果。
 */
public interface SummaryTraceLogger {
    /**
     * 记录基础摘要追踪日志。
     */
    void log(String sessionId,
             Long planId,
             Long nodeId,
             String userInput,
             String assistantReply,
             StructuredContextPackage contextPackage,
             SummaryResult summaryResult,
             String triggerSource);

    /**
     * 记录带扩展链路元信息的摘要追踪日志。
     */
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
