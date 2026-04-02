package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.RerankTraceLogger;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.memory.RuntimeAuditService;

@Service
@RequiredArgsConstructor
public class RuntimeRerankTraceLogger implements RerankTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult) {
        try {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "RERANK_TRACE",
                    "global context rerank selected candidates",
                    objectMapper.writeValueAsString(rerankResult == null ? java.util.Map.of() : rerankResult)
            );
        } catch (Exception ignore) {
        }
    }
}

