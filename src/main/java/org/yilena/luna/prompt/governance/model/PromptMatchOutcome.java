package org.yilena.luna.prompt.governance.model;

import lombok.Getter;

@Getter
/**
 * 提示词匹配结果模型，负责描述单条提示词在本次解析中的命中结果、原因和策略是否介入，
 * 便于预览和调试阶段解释匹配过程。
 */
public final class PromptMatchOutcome {
    /**
     * 是否命中当前上下文。
     */
    private final boolean matched;
    /**
     * 命中原因说明。
     */
    private final String matchReason;
    /**
     * 未命中时的拒绝原因。
     */
    private final String rejectedReason;
    /**
     * 本次命中是否受策略包含或排除影响。
     */
    private final boolean policyApplied;

    private PromptMatchOutcome(boolean matched, String matchReason, String rejectedReason, boolean policyApplied) {
        this.matched = matched;
        this.matchReason = matchReason;
        this.rejectedReason = rejectedReason;
        this.policyApplied = policyApplied;
    }

    public static PromptMatchOutcome matched(String reason) {
        return new PromptMatchOutcome(true, reason, "", false);
    }

    public static PromptMatchOutcome matched(String reason, boolean policyApplied) {
        return new PromptMatchOutcome(true, reason, "", policyApplied);
    }

    public static PromptMatchOutcome rejected(String reason) {
        return new PromptMatchOutcome(false, "", reason, false);
    }
}
