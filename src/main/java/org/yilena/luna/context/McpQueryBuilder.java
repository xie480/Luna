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
     * 构建面向 MCP（Model Context Protocol）的查询表达式
     *
     * 该方法将输入重构结果中的核心任务目标、实体、约束和阶段信息组合成结构化的查询字符串，
     * 用于后续的 MCP 能力检索和资源匹配。查询格式采用管道符分隔的键值对形式，
     * 便于服务端进行精确的语义匹配和路由决策。
     *
     * @param reconstructionResult 输入重构结果，包含任务目标、实体、约束等结构化信息
     * @param taskState 当前任务的运行时状态，用于标识任务所处阶段
     * @return 格式化后的 MCP 查询字符串，如果重构结果不满足就绪条件则返回空字符串
     */
    public String build(InputReconstructionResult reconstructionResult, TaskRuntimeState taskState) {
        // 第一步：校验重构结果的就绪状态
        // 如果重构结果不足以支撑检索（如缺少明确任务目标），则返回空查询
        // 避免基于噪声或不完整的条件进行误召回，保证检索质量
        if (!isReconstructionReady(reconstructionResult)) {
            return "";
        }

        // 第二步：提取并格式化各个查询维度
        // 先确定主查询语义作为基础，再补充实体、约束和阶段标签等辅助信息

        // 解析基础查询语义：从重构结果中提取核心的查询意图表达
        String base = resolveBaseQuery(reconstructionResult);

        // 格式化已澄清的实体列表：将结构化实体转换为字符串表示
        String entities = formatEntities(reconstructionResult.getClarifiedEntities());

        // 格式化业务约束列表：将约束条件转换为字符串表示
        String constraints = formatList(reconstructionResult.getBusinessConstraints());

        // 提取时间范围信息：标识任务相关的时间上下文
        String timeScope = safe(reconstructionResult.getTimeScope());

        // 提取蓝图提示：提供任务执行的架构或方案层面的指导信息
        String blueprintHint = safe(reconstructionResult.getBlueprintHint());

        // 提取明确的任务目标：用户或系统定义的清晰任务描述
        String explicitGoal = safe(reconstructionResult.getExplicitTaskGoal());

        // 第三步：组装最终的 MCP 查询表达式
        // 采用管道符分隔的键值对格式，每个维度独立标注，便于服务端解析和匹配
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
