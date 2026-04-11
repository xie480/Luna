package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.AssembledContext;

@Value
@Builder
/**
 * 主模型编排结果模型，负责承载主模型阶段是否被阻断、最终提示词与回复内容，
 * 供单轮流水线和状态写回阶段继续处理。
 */
public class MainModelOrchestrationResult {
    /**
     * 是否因前置条件未满足而阻断主模型执行。
     */
    boolean blocked;
    /**
     * 阻断原因说明。
     */
    String blockedReason;
    /**
     * 主模型执行前最终组装的上下文。
     */
    AssembledContext assembledContext;
    /**
     * 最终上下文快照标识。
     */
    String finalSnapshotId;
    /**
     * 最终提交给主模型的提示词。
     */
    String finalPrompt;
    /**
     * 主模型原始响应文本。
     */
    String rawResponse;
    /**
     * 校验后的有效响应文本。
     */
    String validResponse;
    /**
     * 提取出的最终回复正文。
     */
    String replyText;
}
