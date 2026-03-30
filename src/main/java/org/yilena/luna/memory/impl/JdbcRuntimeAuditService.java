package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.mapper.RuntimeAuditMapper;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;

@Service
@RequiredArgsConstructor
public class JdbcRuntimeAuditService implements RuntimeAuditService {

    private final RuntimeAuditMapper runtimeAuditMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage) {
        if (contextPackage == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(contextPackage);
            runtimeAuditMapper.insertContextSnapshot(sessionId, payload);
        } catch (Exception ignore) {
        }
    }

    @Override
    public void persistDecisionRecord(String sessionId, String decisionType, String decisionReason, String decisionPayloadJson) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String payload = normalizePayload(decisionPayloadJson);
            runtimeAuditMapper.insertDecisionRecord(sessionId, decisionType, decisionReason, payload);
        } catch (Exception ignore) {
        }
    }

    @Override
    public void persistToolExecutionTrace(String sessionId,
                                          String toolName,
                                          String callStatus,
                                          String normalizedInputJson,
                                          String normalizedOutputJson,
                                          String errorMessage,
                                          Long latencyMs) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String safeInput = normalizePayload(normalizedInputJson);
            String safeOutput = normalizePayload(normalizedOutputJson);
            runtimeAuditMapper.insertToolExecutionTrace(
                    sessionId,
                    toolName == null || toolName.isBlank() ? "agent_tool_chain" : toolName,
                    callStatus == null || callStatus.isBlank() ? "UNKNOWN" : callStatus,
                    safeInput,
                    safeOutput,
                    errorMessage,
                    latencyMs
            );
        } catch (Exception ignore) {
        }
    }

    private String normalizePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return "{}";
        }
        try {
            objectMapper.readTree(rawPayload);
            return rawPayload;
        } catch (Exception parseIgnore) {
            try {
                return objectMapper.writeValueAsString(java.util.Map.of("raw", rawPayload));
            } catch (Exception writeIgnore) {
                return "{}";
            }
        }
    }
}
