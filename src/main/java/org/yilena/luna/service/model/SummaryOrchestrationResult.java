package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.model.ContextState;

@Value
@Builder
/**
 * 摘要编排结果模型，负责汇总摘要阶段产出的上下文包、摘要结果和上下文状态，
 * 供后续状态写回与历史替换逻辑使用。
 */
public class SummaryOrchestrationResult {
    /**
     * 摘要阶段使用的结构化上下文包。
     */
    StructuredContextPackage contextPackage;
    /**
     * 生成的摘要结果。
     */
    SummaryResult summaryResult;
    /**
     * 更新后的上下文状态。
     */
    ContextState contextState;
}
