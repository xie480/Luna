package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 该组件负责将输入重构结果整理为 MCP 检索查询语句，为能力发现阶段提供结构化检索条件。
 */
@Component
public class McpQueryBuilder {

    /**
     * 组合核心任务目标、实体、约束和阶段信息，生成面向 MCP 的查询表达式。
     */
    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        /**
         * 重构结果不足以支撑检索时返回空查询，避免基于噪声条件误召回。
         */
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }
        /**
         * 先确定主查询语义，再补充实体、约束和阶段标签，便于服务端做更精确匹配。
         */
        String base = resolveBaseQuery(reconstructionResult);
        String entities = formatEntities(reconstructionResult.getClarifiedEntities());
        String constraints = formatList(reconstructionResult.getBusinessConstraints());
        String timeScope = safe(reconstructionResult.getTimeScope());
        String blueprintHint = safe(reconstructionResult.getBlueprintHint());
        String explicitGoal = safe(reconstructionResult.getExplicitTaskGoal());
        return base
                + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name())
                + " | explicit_task_goal=" + explicitGoal
                + " | clarified_entities=" + entities
                + " | business_constraints=" + constraints
                + " | time_scope=" + timeScope
                + " | blueprint_hint=" + blueprintHint;
    }

    /**
     * 从多个候选语义字段中挑选最适合的检索主语句，优先使用更明确的重写结果。
     */
    private String resolveBaseQuery(InputReconstructionResult reconstructionResult) {
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }
        List<String> candidates = List.of(
                safe(reconstructionResult.getReformulatedQueryForMcp()),
                safe(reconstructionResult.getExplicitTaskGoal()),
                safe(reconstructionResult.getNormalizedUserIntent()),
                safe(reconstructionResult.getBlueprintHint())
        );
        for (String candidate : candidates) {
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private boolean isReconstructionReady(InputReconstructionResult reconstructionResult) {
        return reconstructionResult != null && !safe(reconstructionResult.getExplicitTaskGoal()).isBlank();
    }

    private String formatEntities(Map<String, String> entities) {
        if (entities == null || entities.isEmpty()) {
            return "[]";
        }
        return entities.entrySet().stream()
                .map(entry -> safe(entry.getKey()) + "=" + safe(entry.getValue()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String formatList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(this::safe)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
