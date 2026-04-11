package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

@Value
@Builder
/**
 * 任务编排结果模型，负责承载用户输入或恢复事件经过任务编排后的核心产物，
 * 包括决策结果、上下文包和恢复信息。
 */
public class TaskOrchestrationResult {
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
     * 是否命中过恢复链路。
     */
    boolean recovered;
    /**
     * 恢复事件类型。
     */
    String recoveryEvent;
    /**
     * 中断原因说明。
     */
    String interruptReason;
}
