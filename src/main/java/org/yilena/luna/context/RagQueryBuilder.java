package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 该组件负责为 RAG 检索生成查询语句，把任务目标、实体和约束整理为知识检索条件。
 */
@Component
public class RagQueryBuilder {

    /**
     * 组合重构后的意图、实体和时效线索，生成面向知识检索的查询表达式。
     */
    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        /**
         * 缺少核心任务目标时不继续构造查询，避免知识检索偏离当前话题。
         */
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }
        /**
         * 先确定最合适的主查询，再补充实体、约束和时间范围，让召回结果更贴近业务问题。
         */
        String base = resolveBaseQuery(reconstructionResult);
        String entities = formatEntities(reconstructionResult.getClarifiedEntities());
        String constraints = formatList(reconstructionResult.getBusinessConstraints());
        String timeScope = safe(reconstructionResult.getTimeScope());
        String blueprintHint = safe(reconstructionResult.getBlueprintHint());
        return base
                + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name())
                + " | clarified_entities=" + entities
                + " | business_constraints=" + constraints
                + " | time_scope=" + timeScope
                + " | blueprint_hint=" + blueprintHint;
    }

    /**
     * 在多种重写结果中选择最适合知识检索的基础查询语句。
     */
    private String resolveBaseQuery(InputReconstructionResult reconstructionResult) {
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }
        List<String> candidates = List.of(
                safe(reconstructionResult.getReformulatedQueryForRag()),
                safe(reconstructionResult.getNormalizedUserIntent()),
                safe(reconstructionResult.getExplicitTaskGoal()),
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
