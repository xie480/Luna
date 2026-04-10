package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Data;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

/**
 * 该模型用于承载会话编排决策结果，汇总会话标识、任务状态和上下文包。
 */
@Data
@Builder
public class OrchestrationDecision {
    /**
     * 当前会话标识。
     */
    private String sessionId;
    /**
     * 编排后确定的任务运行状态。
     */
    private TaskRuntimeState taskState;
    /**
     * 关系型记忆侧的运行状态。
     */
    private RelationalRuntimeState relationalState;
    /**
     * 编排阶段产出的结构化上下文包。
     */
    private StructuredContextPackage contextPackage;
}
