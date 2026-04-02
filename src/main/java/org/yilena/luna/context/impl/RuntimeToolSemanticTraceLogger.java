package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.RuntimeAuditService;

@Service
@RequiredArgsConstructor
public class RuntimeToolSemanticTraceLogger implements ToolSemanticTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
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

