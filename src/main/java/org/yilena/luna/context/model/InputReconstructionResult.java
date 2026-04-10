package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载用户输入重构结果，把原始表达整理为可用于检索、规划和工具决策的结构化语义。
 */
@Value
@Builder
public class InputReconstructionResult {
    /**
     * 归一化后的用户意图描述。
     */
    String normalizedUserIntent;
    /**
     * 明确提炼出的任务目标。
     */
    String explicitTaskGoal;
    /**
     * 已澄清的关键实体映射。
     */
    Map<String, String> clarifiedEntities;
    /**
     * 当前仍待补充的槽位信息。
     */
    List<String> missingSlots;
    /**
     * 任务涉及的时间范围。
     */
    String timeScope;
    /**
     * 已识别的业务约束列表。
     */
    List<String> businessConstraints;
    /**
     * 面向 RAG 检索重写后的查询语句。
     */
    String reformulatedQueryForRag;
    /**
     * 面向 MCP 检索重写后的查询语句。
     */
    String reformulatedQueryForMcp;
    /**
     * 对规划或蓝图生成有帮助的提示信息。
     */
    String blueprintHint;
    /**
     * 输入意图重构的置信度。
     */
    double intentConfidence;
}
