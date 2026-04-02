package org.yilena.luna.router;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.CapabilityMapper;
import org.yilena.luna.service.CapabilityCatalogSyncService;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DefaultCapabilityPolicyRouterService implements CapabilityPolicyRouterService {

    private final CapabilityMapper capabilityMapper;
    private final CapabilityCatalogSyncService capabilityCatalogSyncService;
    private final ObjectMapper objectMapper;

    @Value("${luna.capability.policy.role-mapping-json:{}}")
    private String principalRoleMappingJson;

    @Override
    public List<Map<String, Object>> routeForContext(String sessionId,
                                                     String query,
                                                     TaskRuntimeState taskState,
                                                     RelationalRuntimeState relationalState,
                                                     int limit) {
        List<Map<String, Object>> rows = loadBaseCandidates(query, limit);
        rows = filterByAuthorization(rows);
        return rankByPolicy(rows, query, taskState, relationalState, false, limit);
    }

    @Override
    public List<Map<String, Object>> routeForExecution(String sessionId,
                                                       String query,
                                                       TaskRuntimeState taskState,
                                                       RelationalRuntimeState relationalState,
                                                       int limit) {
        List<Map<String, Object>> rows = loadBaseCandidates(query, limit);
        rows = filterByAuthorization(rows);
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

    private List<Map<String, Object>> filterByAuthorization(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        String principal = normalize(AuthContextHolder.getPrincipalKey());
        Set<String> roles = resolvePrincipalRoles(principal);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (isAllowed(row, principal, roles)) {
                out.add(row);
            }
        }
        return out;
    }

    private boolean isAllowed(Map<String, Object> row, String principal, Set<String> roles) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        Map<String, Object> metadata = parseMetadata(row.get("metadata_json"));
        if (metadata.isEmpty()) {
            return true;
        }

        Set<String> deniedPrincipals = extractStringSet(metadata.get("deniedPrincipals"));
        if (matchPrincipal(principal, deniedPrincipals)) {
            return false;
        }

        Set<String> allowedPrincipals = extractStringSet(metadata.get("allowedPrincipals"));
        if (!allowedPrincipals.isEmpty() && !matchPrincipal(principal, allowedPrincipals)) {
            return false;
        }

        Set<String> requiredRoles = extractStringSet(metadata.get("requiredRoles"));
        if (requiredRoles.isEmpty()) {
            return true;
        }
        for (String role : requiredRoles) {
            if ("*".equals(role) || roles.contains(role.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchPrincipal(String principal, Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        if (candidates.contains("*")) {
            return true;
        }
        if (principal == null || principal.isBlank()) {
            return false;
        }
        if (candidates.contains(principal)) {
            return true;
        }
        int idx = principal.indexOf(':');
        if (idx > 0 && idx + 1 < principal.length()) {
            return candidates.contains(principal.substring(idx + 1));
        }
        return false;
    }

    private Set<String> resolvePrincipalRoles(String principal) {
        Set<String> out = new HashSet<>();
        if (principal != null && !principal.isBlank()) {
            out.add(principal);
        }
        Map<String, List<String>> mapping = parseRoleMapping();
        List<String> direct = mapping.getOrDefault(principal, List.of());
        for (String role : direct) {
            if (role != null && !role.isBlank()) {
                out.add(role.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private Map<String, List<String>> parseRoleMapping() {
        if (principalRoleMappingJson == null || principalRoleMappingJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(principalRoleMappingJson, new TypeReference<>() {
            });
            Map<String, List<String>> out = new HashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                Set<String> roles = extractStringSet(entry.getValue());
                out.put(normalize(entry.getKey()), new ArrayList<>(roles));
            }
            return out;
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private Map<String, Object> parseMetadata(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new HashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<>() {
            });
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    private Set<String> extractStringSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String text = normalize(item == null ? "" : String.valueOf(item));
                if (!text.isBlank()) {
                    out.add(text);
                }
            }
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<Object> parsed = objectMapper.readValue(text, new TypeReference<>() {
                });
                for (Object item : parsed) {
                    String one = normalize(item == null ? "" : String.valueOf(item));
                    if (!one.isBlank()) {
                        out.add(one);
                    }
                }
                return out;
            } catch (Exception ignore) {
                return Set.of();
            }
        }
        Arrays.stream(text.split(","))
                .map(this::normalize)
                .filter(s -> !s.isBlank())
                .forEach(out::add);
        return out;
    }

    private void syncAllCapabilities() {
        capabilityCatalogSyncService.syncFromServers();
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
