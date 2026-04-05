package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.memory.RuntimeAuditService;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeContextTraceLogger implements ContextTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext) {
        log(sessionId, planId, nodeId, assembledContext, Map.of());
    }

    @Override
    public void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext, Map<String, Object> traceMeta) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("traceId", "")));
            payload.put("traceLayer", "CONTEXT_ASSEMBLY");
            payload.put("nodeId", nodeId);
            payload.put("sections", assembledContext == null ? java.util.Map.of() : assembledContext.getSections());
            payload.put("candidatePool", assembledContext == null ? java.util.Map.of() : assembledContext.getCandidatePool());
            payload.put("snapshotId", assembledContext == null ? "" : assembledContext.getSnapshotId());
            payload.put("recoveryEvent", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("recoveryEvent", "")));
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
