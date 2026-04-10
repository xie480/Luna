package org.yilena.luna.context;

import org.yilena.luna.context.model.ContextRerankResult;
import java.util.Map;

/**
 * 重排追踪日志接口，负责记录上下文重排阶段的选择结果与链路元信息。
 */
public interface RerankTraceLogger {
    /**
     * 记录基础重排追踪日志。
     */
    void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult);

    /**
     * 记录带扩展元信息的重排追踪日志。
     */
    default void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult, Map<String, Object> traceMeta) {
        log(sessionId, planId, nodeId, rerankResult);
    }
}
