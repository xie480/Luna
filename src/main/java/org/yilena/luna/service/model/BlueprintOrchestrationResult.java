package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

@Value
@Builder
/**
 * 蓝图编排结果模型，负责汇总蓝图生成阶段涉及的上下文、输入重构、节点工作集和草稿结果，
 * 供后续主规划流程直接消费。
 */
public class BlueprintOrchestrationResult {
    /**
     * 本次蓝图阶段使用的结构化上下文包。
     */
    StructuredContextPackage contextPackage;
    /**
     * 输入重构结果。
     */
    InputReconstructionResult reconstructionResult;
    /**
     * 会话编排决策结果。
     */
    OrchestrationDecision decision;
    /**
     * 当前节点工作集结果。
     */
    NodeWorksetResult nodeWorksetResult;
    /**
     * 输出的蓝图草稿。
     */
    BlueprintDraft blueprintDraft;
}
