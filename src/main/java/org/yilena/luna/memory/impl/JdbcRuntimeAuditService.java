package org.yilena.luna.memory.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;

@Service
@RequiredArgsConstructor
public class JdbcRuntimeAuditService implements RuntimeAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage) {
        if (contextPackage == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(contextPackage);
            jdbcTemplate.update(
                    "insert into plan_context_snapshot(plan_id, node_id, session_id, context_package_json, created_at) " +
                            "select current_plan_id, null, ?, cast(? as jsonb), current_timestamp " +
                            "from agent_session where session_id = ?",
                    sessionId, payload, sessionId
            );
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
            jdbcTemplate.update(
                    "insert into plan_decision_record(plan_id, node_id, decision_type, decision_reason, decision_payload, created_at) " +
                            "select current_plan_id, null, ?, ?, cast(? as jsonb), current_timestamp " +
                            "from agent_session where session_id = ?",
                    decisionType, decisionReason, payload, sessionId
            );
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
            jdbcTemplate.update(
                    "insert into tool_execution_trace(plan_id, node_id, session_id, tool_name, call_status, normalized_input, normalized_output, error_message, latency_ms, created_at) " +
                            "select current_plan_id, null, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, current_timestamp " +
                            "from agent_session where session_id = ?",
                    sessionId,
                    toolName == null || toolName.isBlank() ? "agent_tool_chain" : toolName,
                    callStatus == null || callStatus.isBlank() ? "UNKNOWN" : callStatus,
                    safeInput,
                    safeOutput,
                    errorMessage,
                    latencyMs,
                    sessionId
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
