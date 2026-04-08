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
        return savePreToolDecisionSnapshot(
                sessionId,
                planId,
                nodeId,
                userInput,
                reconstructedMcpQuery,
                executionCandidates,
                extra,
                Map.of()
        );
    }

    @Override
    public String savePreToolDecisionSnapshot(String sessionId,
                                              Long planId,
                                              Long nodeId,
                                              String userInput,
                                              String reconstructedMcpQuery,
                                              List<Map<String, Object>> executionCandidates,
                                              Map<String, Object> extra,
                                              Map<String, Object> rawToolResultChannel) {
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
            payload.put("rawToolResultChannel", rawToolResultChannel == null ? Map.of() : rawToolResultChannel);
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
    public String saveToolDecisionContextSnapshot(String sessionId,
                                                  Long planId,
                                                  Long nodeId,
                                                  String assembledDecisionContext,
                                                  Map<String, List<String>> sections,
                                                  List<Map<String, Object>> executionCandidates,
                                                  Map<String, Integer> sectionTokenCounts,
                                                  Map<String, Double> sectionTokenRatios,
                                                  Map<String, Object> extra) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotType", "TOOL_DECISION_CONTEXT");
            payload.put("assembledDecisionContext", assembledDecisionContext == null ? "" : assembledDecisionContext);
            payload.put("sections", sections == null ? Map.of() : sections);
            payload.put("executionCandidates", executionCandidates == null ? List.of() : executionCandidates);
            payload.put("sectionTokenCounts", sectionTokenCounts == null ? Map.of() : sectionTokenCounts);
            payload.put("sectionTokenRatios", sectionTokenRatios == null ? Map.of() : sectionTokenRatios);
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
        return saveFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                Map.of()
        );
    }

    @Override
    public String saveFinalSnapshot(String sessionId,
                                    Long planId,
                                    Long nodeId,
                                    AssembledContext assembledContext,
                                    String prompt,
                                    Map<String, Integer> sectionTokenCounts,
                                    Map<String, Double> sectionTokenRatios,
                                    Map<String, Object> rawToolResultChannel) {
        return saveFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                rawToolResultChannel,
                Map.of()
        );
    }

    @Override
    public String saveFinalSnapshot(String sessionId,
                                    Long planId,
                                    Long nodeId,
                                    AssembledContext assembledContext,
                                    String prompt,
                                    Map<String, Integer> sectionTokenCounts,
                                    Map<String, Double> sectionTokenRatios,
                                    Map<String, Object> rawToolResultChannel,
                                    Map<String, List<String>> activeRefs) {
        return saveFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                rawToolResultChannel,
                activeRefs,
                Map.of()
        );
    }

    @Override
    public String saveFinalSnapshot(String sessionId,
                                    Long planId,
                                    Long nodeId,
                                    AssembledContext assembledContext,
                                    String prompt,
                                    Map<String, Integer> sectionTokenCounts,
                                    Map<String, Double> sectionTokenRatios,
                                    Map<String, Object> rawToolResultChannel,
                                    Map<String, List<String>> activeRefs,
                                    Map<String, Object> structuredRecoveryPayload) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotType", "FINAL_MODEL_CONTEXT");
            payload.put("prompt", prompt == null ? "" : prompt);
            payload.put("sections", assembledContext == null ? Map.of() : assembledContext.getSections());
            payload.put("canonicalSections", assembledContext == null ? Map.of() : assembledContext.getCanonicalSections());
            Map<String, Object> promptAssemblyMeta = assembledContext == null ? Map.of() : normalizePromptAssemblyMeta(assembledContext.getPromptAssemblyMeta());
            payload.put("promptRefs", promptAssemblyMeta.getOrDefault("promptRefs", List.of()));
            payload.put("policyId", promptAssemblyMeta.getOrDefault("policyId", ""));
            payload.put("assemblerVersion", promptAssemblyMeta.getOrDefault("assemblerVersion", ""));
            payload.put("slotMapping", promptAssemblyMeta.getOrDefault("slotMapping", Map.of()));
            payload.put("promptAssemblyMeta", promptAssemblyMeta);
            payload.put("sectionTokenCounts", sectionTokenCounts == null ? Map.of() : sectionTokenCounts);
            payload.put("sectionTokenRatios", sectionTokenRatios == null ? Map.of() : sectionTokenRatios);
            payload.put("rawToolResultChannel", rawToolResultChannel == null ? Map.of() : rawToolResultChannel);
            Map<String, List<String>> normalizedActiveRefs = normalizeActiveRefs(activeRefs);
            payload.put("activeRefs", normalizedActiveRefs);
            payload.put("activeKnowledgeRefs", normalizedActiveRefs.getOrDefault("activeKnowledgeRefs", List.of()));
            payload.put("activeMemoryRefs", normalizedActiveRefs.getOrDefault("activeMemoryRefs", List.of()));
            payload.put("activeToolEvidenceRefs", normalizedActiveRefs.getOrDefault("activeToolEvidenceRefs", List.of()));
            payload.put("activeMcpPromptRefs", normalizedActiveRefs.getOrDefault("activeMcpPromptRefs", List.of()));
            payload.put("activeMcpResourceRefs", normalizedActiveRefs.getOrDefault("activeMcpResourceRefs", List.of()));
            payload.put("activeMcpWorkflowRefs", normalizedActiveRefs.getOrDefault("activeMcpWorkflowRefs", List.of()));
            payload.put("activeMcpToolRefs", normalizedActiveRefs.getOrDefault("activeMcpToolRefs", List.of()));
            payload.put("activeMcpResourceRefsLegacy", normalizedActiveRefs.getOrDefault("activeMcpResourceRefsLegacy", List.of()));
            payload.put("structuredRecoveryPayload", structuredRecoveryPayload == null ? Map.of() : structuredRecoveryPayload);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizePromptAssemblyMeta(Map<String, Object> promptAssemblyMeta) {
        if (promptAssemblyMeta == null || promptAssemblyMeta.isEmpty()) {
            return Map.of(
                    "promptRefs", List.of(),
                    "policyId", "",
                    "assemblerVersion", "",
                    "slotMapping", Map.of()
            );
        }
        Object refsRaw = promptAssemblyMeta.get("promptRefs");
        List<Map<String, Object>> refs = refsRaw instanceof List<?> list
                ? list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> (Map<String, Object>) item)
                .toList()
                : List.of();
        Object slotMappingRaw = promptAssemblyMeta.get("slotMapping");
        Map<String, List<Map<String, Object>>> slotMapping = slotMappingRaw instanceof Map<?, ?> rawMap
                ? rawMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !String.valueOf(entry.getKey()).isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        entry -> toMapList(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                : Map.of();
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("promptRefs", refs);
        normalized.put("policyId", stringValue(promptAssemblyMeta.get("policyId")));
        normalized.put("assemblerVersion", stringValue(promptAssemblyMeta.get("assemblerVersion")));
        normalized.put("slotMapping", slotMapping);
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private Map<String, List<String>> normalizeActiveRefs(Map<String, List<String>> activeRefs) {
        if (activeRefs == null || activeRefs.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : activeRefs.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            List<String> refs = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream()
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .toList();
            normalized.put(key, refs);
        }
        return normalized;
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
