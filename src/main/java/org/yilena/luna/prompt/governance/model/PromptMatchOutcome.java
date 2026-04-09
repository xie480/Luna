package org.yilena.luna.prompt.governance.model;

import lombok.Getter;

@Getter
public final class PromptMatchOutcome {
    private final boolean matched;
    private final String matchReason;
    private final String rejectedReason;
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
