package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.RuntimeAuditService;

@Service
@RequiredArgsConstructor
/**
 * 工具语义追踪日志实现，负责记录工具结果翻译后的结构化语义，
 * 便于回查工具执行对后续流程的影响。
 */
public class RuntimeToolSemanticTraceLogger implements ToolSemanticTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 将工具语义结果写入运行态审计日志。
     */
    public void log(String sessionId, Long planId, Long nodeId, ToolSemanticResult semanticResult) {
        try {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_TRACE",
                    "tool semantic interpretation generated",
                    objectMapper.writeValueAsString(semanticResult == null ? java.util.Map.of() : semanticResult)
            );
        } catch (Exception ignore) {
        }
    }
}
