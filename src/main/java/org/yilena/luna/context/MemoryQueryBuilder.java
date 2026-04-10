package org.yilena.luna.context;

import org.springframework.stereotype.Component;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 该组件负责为记忆检索构建查询语句，突出未决槽位、约束和当前任务阶段等记忆补全线索。
 */
@Component
public class MemoryQueryBuilder {

    /**
     * 将输入重构结果转换为面向记忆检索的查询表达式，帮助召回与当前任务最相关的历史信息。
     */
    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        /**
         * 缺少明确任务目标时不发起记忆查询，避免误召回无关内容。
         */
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }
        /**
         * 主查询突出目标语义，再补充未决槽位、实体和约束，方便检索聚焦缺失上下文。
         */
        String base = resolveBaseQuery(reconstructionResult);
        String pending = formatList(reconstructionResult.getMissingSlots());
        String entities = formatEntities(reconstructionResult.getClarifiedEntities());
        String constraints = formatList(reconstructionResult.getBusinessConstraints());
        return base
                + " | memory_focus=true"
                + " | task_stage=" + (taskState == null ? "UNKNOWN" : taskState.name())
                + " | unresolved_slots=" + pending
                + " | clarified_entities=" + entities
                + " | business_constraints=" + constraints;
    }

    /**
     * 从多个候选字段中挑选最能代表记忆检索意图的主查询语句。
     */
    private String resolveBaseQuery(InputReconstructionResult reconstructionResult) {
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }
        List<String> candidates = List.of(
                safe(reconstructionResult.getExplicitTaskGoal()),
                safe(reconstructionResult.getNormalizedUserIntent()),
                safe(reconstructionResult.getReformulatedQueryForRag())
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
