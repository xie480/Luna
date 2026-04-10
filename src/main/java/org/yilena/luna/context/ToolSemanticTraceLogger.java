package org.yilena.luna.context;

import org.yilena.luna.context.model.ToolSemanticResult;

/**
 * 工具语义追踪日志接口，负责记录工具结果语义化后的结构化内容。
 */
public interface ToolSemanticTraceLogger {
    /**
     * 记录一次工具语义翻译结果。
     */
    void log(String sessionId, Long planId, Long nodeId, ToolSemanticResult semanticResult);
}
