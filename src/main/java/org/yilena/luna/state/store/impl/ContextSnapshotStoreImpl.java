package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.mapper.RuntimeAuditMapper;
import org.yilena.luna.state.model.ContextSnapshot;
import org.yilena.luna.state.store.ContextSnapshotStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContextSnapshotStoreImpl implements ContextSnapshotStore {

    private final RuntimeAuditMapper runtimeAuditMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String savePreToolDecisionSnapshot(String sessionId,
                                              Long planId,
                                              Long nodeId,
                                              String userInput,
                                              String reconstructedMcpQuery,
                                              List<Map<String, Object>> executionCandidates,
                                              Map<String, Object> extra) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotType", "PRE_TOOL_DECISION_CONTEXT");
            payload.put("userInput", userInput == null ? "" : userInput);
            payload.put("reconstructedMcpQuery", reconstructedMcpQuery == null ? "" : reconstructedMcpQuery);
            payload.put("executionCandidates", executionCandidates == null ? List.of() : executionCandidates);
            payload.put("extra", extra == null ? Map.of() : extra);
            Long snapshotId = runtimeAuditMapper.insertContextSnapshotAndReturnId(
                    sessionId,
                    planId,
                    nodeId,
                    objectMapper.writeValueAsString(payload)
            );
            if (snapshotId == null) {
                runtimeAuditMapper.insertContextSnapshot(
                        sessionId,
                        planId,
                        nodeId,
                        objectMapper.writeValueAsString(payload)
                );
                return "";
            }
            return String.valueOf(snapshotId);
        } catch (Exception ignore) {
            return "";
        }
    }

    @Override
    public String saveFinalSnapshot(String sessionId,
                                    Long planId,
                                    Long nodeId,
                                    AssembledContext assembledContext,
                                    String prompt,
                                    Map<String, Integer> sectionTokenCounts,
                                    Map<String, Double> sectionTokenRatios) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotType", "FINAL_MODEL_CONTEXT");
            payload.put("prompt", prompt == null ? "" : prompt);
            payload.put("sections", assembledContext == null ? Map.of() : assembledContext.getSections());
            payload.put("sectionTokenCounts", sectionTokenCounts == null ? Map.of() : sectionTokenCounts);
            payload.put("sectionTokenRatios", sectionTokenRatios == null ? Map.of() : sectionTokenRatios);
            Long snapshotId = runtimeAuditMapper.insertContextSnapshotAndReturnId(
                    sessionId,
                    planId,
                    nodeId,
                    objectMapper.writeValueAsString(payload)
            );
            if (snapshotId == null) {
                runtimeAuditMapper.insertContextSnapshot(
                        sessionId,
                        planId,
                        nodeId,
                        objectMapper.writeValueAsString(payload)
                );
                return "";
            }
            return String.valueOf(snapshotId);
        } catch (Exception ignore) {
            return "";
        }
    }

    @Override
    public ContextSnapshot load(String sessionId, String snapshotId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Long id = parseLong(snapshotId);
        if (id == null) {
            return null;
        }
        return parseSnapshotRow(runtimeAuditMapper.selectContextSnapshotById(sessionId, id));
    }

    @Override
    public ContextSnapshot loadLatest(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return parseSnapshotRow(runtimeAuditMapper.selectLatestContextSnapshotBySession(sessionId));
    }

    private ContextSnapshot parseSnapshotRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = parsePayload(stringValue(row.get("context_package_json")));
        return ContextSnapshot.builder()
                .snapshotId(stringValue(row.get("id")))
                .sessionId(stringValue(row.get("session_id")))
                .planId(parseLong(row.get("plan_id")))
                .nodeId(parseLong(row.get("node_id")))
                .payload(payload)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(rawJson, Map.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of("raw", rawJson);
        } catch (Exception ignore) {
            return Map.of("raw", rawJson);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }
}
