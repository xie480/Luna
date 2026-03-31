package org.yilena.luna.router;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.CapabilityMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultCapabilityPolicyRouterService implements CapabilityPolicyRouterService {

    private final CapabilityMapper capabilityMapper;

    @Override
    public List<Map<String, Object>> routeForContext(String sessionId,
                                                     String query,
                                                     TaskRuntimeState taskState,
                                                     RelationalRuntimeState relationalState,
                                                     int limit) {
        List<Map<String, Object>> rows = loadBaseCandidates(query, limit);
        return rankByPolicy(rows, query, taskState, relationalState, false, limit);
    }

    @Override
    public List<Map<String, Object>> routeForExecution(String sessionId,
                                                       String query,
                                                       TaskRuntimeState taskState,
                                                       RelationalRuntimeState relationalState,
                                                       int limit) {
        List<Map<String, Object>> rows = loadBaseCandidates(query, limit);
        return rankByPolicy(rows, query, taskState, relationalState, true, limit);
    }

    @Override
    public boolean shouldTriggerPlanOrchestration(String query, TaskRuntimeState taskState) {
        if (taskState != TaskRuntimeState.PLANNING
                && taskState != TaskRuntimeState.REPLANNING
                && taskState != TaskRuntimeState.CONTEXT_BUILDING) {
            return false;
        }
        String text = normalize(query);
        return containsAny(text, "计划", "规划", "方案", "roadmap", "plan", "milestone", "拆解", "分阶段", "replan");
    }

    private List<Map<String, Object>> loadBaseCandidates(String query, int limit) {
        try {
            syncAllCapabilities();
            int safeLimit = Math.max(8, Math.min(limit <= 0 ? 24 : limit, 80));
            String text = query == null ? "" : query.trim();
            if (text.isBlank()) {
                return capabilityMapper.selectTopCapabilities();
            }
            return capabilityMapper.searchCapabilityCandidates(text, Math.max(24, safeLimit * 3));
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    private void syncAllCapabilities() {
        capabilityMapper.syncToolsIntoRegistry();
        capabilityMapper.syncPromptsIntoRegistry();
        capabilityMapper.syncResourcesIntoRegistry();
        capabilityMapper.syncWorkflowsIntoRegistry();
        capabilityMapper.syncTaskStrategiesIntoRegistry();
        capabilityMapper.syncRelationalStrategiesIntoRegistry();
    }

    private List<Map<String, Object>> rankByPolicy(List<Map<String, Object>> rows,
                                                   String query,
                                                   TaskRuntimeState taskState,
                                                   RelationalRuntimeState relationalState,
                                                   boolean executionOnly,
                                                   int limit) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> preferredTypes = preferredTypes(taskState, relationalState);
        String q = normalize(query);

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt((Map<String, Object> row) -> typePenalty(typeOf(row), preferredTypes))
                .thenComparingInt(row -> keywordPenalty(q, row))
                .thenComparing(row -> String.valueOf(row.getOrDefault("capability_name", ""))));

        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        int safeLimit = Math.max(1, limit <= 0 ? 24 : limit);
        for (Map<String, Object> row : sorted) {
            String type = typeOf(row);
            if (executionOnly && "STRATEGY".equals(type)) {
                continue;
            }
            String capabilityName = String.valueOf(row.getOrDefault("capability_name", ""));
            if (capabilityName.isBlank() || !seen.add(capabilityName)) {
                continue;
            }
            out.add(row);
            if (out.size() >= safeLimit) {
                break;
            }
        }
        return out;
    }

    private List<String> preferredTypes(TaskRuntimeState taskState, RelationalRuntimeState relationalState) {
        if (relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == RelationalRuntimeState.REPAIRING) {
            return List.of("STRATEGY", "PROMPT", "RESOURCE", "WORKFLOW", "TOOL");
        }
        if (taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING) {
            return List.of("STRATEGY", "WORKFLOW", "TOOL", "PROMPT", "RESOURCE");
        }
        if (taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL) {
            return List.of("TOOL", "WORKFLOW", "RESOURCE", "PROMPT", "STRATEGY");
        }
        return List.of("TOOL", "WORKFLOW", "PROMPT", "RESOURCE", "STRATEGY");
    }

    private int typePenalty(String type, List<String> preferredTypes) {
        int idx = preferredTypes.indexOf(type);
        return idx < 0 ? preferredTypes.size() : idx;
    }

    private int keywordPenalty(String query, Map<String, Object> row) {
        if (query.isBlank()) {
            return 0;
        }
        String name = normalize(String.valueOf(row.getOrDefault("capability_name", "")));
        String title = normalize(String.valueOf(row.getOrDefault("title", "")));
        String desc = normalize(String.valueOf(row.getOrDefault("description", "")));
        if (name.contains(query)) {
            return 0;
        }
        if (title.contains(query)) {
            return 1;
        }
        if (desc.contains(query)) {
            return 2;
        }
        return 3;
    }

    private String typeOf(Map<String, Object> row) {
        return String.valueOf(row.getOrDefault("capability_type", "")).toUpperCase(Locale.ROOT);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

