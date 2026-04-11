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
/**
 * 匹配范围模型，负责描述提示词在代理、节点、任务状态、模型家族和人格场景等维度上的适用范围，
 * 用于运行时判断提示词是否命中当前上下文。
 */
public class MatchScope {
    /**
     * 允许命中的代理标识列表。
     */
    private List<String> agents;
    /**
     * 允许命中的节点类型列表。
     */
    private List<String> nodeKinds;
    /**
     * 允许命中的任务状态列表。
     */
    private List<String> taskStates;
    /**
     * 允许命中的模型家族列表。
     */
    private List<String> modelFamilies;
    /**
     * 允许命中的人格标识列表。
     */
    private List<String> personaIds;
    /**
     * 允许命中的场景标识列表。
     */
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
