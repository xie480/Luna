package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.memory.RuntimeAuditService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeStateTransitionTraceLogger implements StateTransitionTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String traceId,
                    String sessionId,
                    Long planId,
                    Long nodeId,
                    String fromTaskState,
                    String toTaskState,
                    String event,
                    String action,
                    String snapshotId,
                    String recoveryEvent) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId == null ? "" : traceId);
            payload.put("sessionId", sessionId);
            payload.put("planId", planId);
            payload.put("nodeId", nodeId);
            payload.put("fromTaskState", fromTaskState == null ? "" : fromTaskState);
            payload.put("toTaskState", toTaskState == null ? "" : toTaskState);
            payload.put("event", event == null ? "" : event);
            payload.put("action", action == null ? "" : action);
            payload.put("snapshotId", snapshotId == null ? "" : snapshotId);
            payload.put("recoveryEvent", recoveryEvent == null ? "" : recoveryEvent);
            payload.put("timestamp", Instant.now().toString());
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "STATE_TRANSITION_TRACE",
                    "state transition index trace",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}

