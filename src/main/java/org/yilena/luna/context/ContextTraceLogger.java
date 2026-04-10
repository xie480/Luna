package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;
import java.util.Map;

/**
 * 上下文追踪日志接口，负责记录上下文组装后的分区内容和候选池信息。
 */
public interface ContextTraceLogger {
    /**
     * 记录基础上下文追踪日志。
     */
    void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext);

    /**
     * 记录带额外链路元信息的上下文追踪日志。
     */
    default void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext, Map<String, Object> traceMeta) {
        log(sessionId, planId, nodeId, assembledContext);
    }
}
