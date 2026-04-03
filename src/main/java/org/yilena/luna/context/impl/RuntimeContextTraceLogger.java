package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.memory.RuntimeAuditService;

@Service
@RequiredArgsConstructor
public class RuntimeContextTraceLogger implements ContextTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("sections", assembledContext == null ? java.util.Map.of() : assembledContext.getSections());
            payload.put("candidatePool", assembledContext == null ? java.util.Map.of() : assembledContext.getCandidatePool());
            payload.put("snapshotId", assembledContext == null ? "" : assembledContext.getSnapshotId());
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "CONTEXT_TRACE",
                    "context sections assembled",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}
