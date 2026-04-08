package org.yilena.luna.prompt.governance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchScope {
    private List<String> agents;
    private List<String> nodeKinds;
    private List<String> taskStates;
    private List<String> modelFamilies;
    private List<String> personaIds;
    private List<String> sceneIds;

    public static MatchScope empty() {
        return MatchScope.builder()
                .agents(List.of())
                .nodeKinds(List.of())
                .taskStates(List.of())
                .modelFamilies(List.of())
                .personaIds(List.of())
                .sceneIds(List.of())
                .build();
    }

    public static MatchScope fromMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return empty();
        }
        return MatchScope.builder()
                .agents(toList(values.get("agents")))
                .nodeKinds(toList(values.get("nodeKinds")))
                .taskStates(toList(values.get("taskStates")))
                .modelFamilies(toList(values.get("modelFamilies")))
                .personaIds(toList(values.get("personaIds")))
                .sceneIds(toList(values.get("sceneIds")))
                .build();
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "agents", safe(agents),
                "nodeKinds", safe(nodeKinds),
                "taskStates", safe(taskStates),
                "modelFamilies", safe(modelFamilies),
                "personaIds", safe(personaIds),
                "sceneIds", safe(sceneIds)
        );
    }

    public boolean isEmpty() {
        return safe(agents).isEmpty()
                && safe(nodeKinds).isEmpty()
                && safe(taskStates).isEmpty()
                && safe(modelFamilies).isEmpty()
                && safe(personaIds).isEmpty()
                && safe(sceneIds).isEmpty();
    }

    private static List<String> toList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object row : list) {
            if (row == null) {
                continue;
            }
            String text = String.valueOf(row).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out.stream().toList();
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
