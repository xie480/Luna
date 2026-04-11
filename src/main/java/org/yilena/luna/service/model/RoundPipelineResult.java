package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

@Value
@Builder
/**
 * 单轮流水线结果模型，负责汇总单轮执行后的工具语义、摘要、主模型输出和上下文结果，
 * 供控制层或后续状态写回阶段统一消费。
 */
public class RoundPipelineResult {
    /**
     * 是否在流水线中被阻断。
     */
    boolean blocked;
    /**
     * 阻断原因说明。
     */
    String blockedReason;
    /**
     * 工具语义分析结果。
     */
    ToolSemanticResult toolSemanticResult;
    /**
     * 预组装摘要结果。
     */
    SummaryResult preAssemblySummary;
    /**
     * 主模型编排结果。
     */
    MainModelOrchestrationResult mainModelResult;
    /**
     * 最终摘要结果。
     */
    SummaryResult summaryResult;
    /**
     * 最终快照标识。
     */
    String finalSnapshotId;
    /**
     * 会话编排决策结果。
     */
    OrchestrationDecision decision;
    /**
     * 结构化上下文包。
     */
    StructuredContextPackage contextPackage;
    /**
     * 输入重构结果。
     */
    InputReconstructionResult reconstructionResult;
    /**
     * 节点工作集结果。
     */
    NodeWorksetResult nodeWorksetResult;
}
