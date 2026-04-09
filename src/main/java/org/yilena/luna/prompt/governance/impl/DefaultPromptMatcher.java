package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.PromptMatcher;
import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptMatchOutcome;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.Set;

final class DefaultPromptMatcher implements PromptMatcher {
    private final AlwaysPromptSelector alwaysPromptSelector;
    private final KeywordPromptMatcher keywordPromptMatcher;
    private final AgentPromptMatcher agentPromptMatcher;
    private final PolicyPromptSelector policyPromptSelector;
    private final boolean policyForceIncludeNonPolicyMode;

    DefaultPromptMatcher(AlwaysPromptSelector alwaysPromptSelector,
                         KeywordPromptMatcher keywordPromptMatcher,
                         AgentPromptMatcher agentPromptMatcher,
                         PolicyPromptSelector policyPromptSelector) {
        this(alwaysPromptSelector, keywordPromptMatcher, agentPromptMatcher, policyPromptSelector, true);
    }

    DefaultPromptMatcher(AlwaysPromptSelector alwaysPromptSelector,
                         KeywordPromptMatcher keywordPromptMatcher,
                         AgentPromptMatcher agentPromptMatcher,
                         PolicyPromptSelector policyPromptSelector,
                         boolean policyForceIncludeNonPolicyMode) {
        this.alwaysPromptSelector = alwaysPromptSelector;
        this.keywordPromptMatcher = keywordPromptMatcher;
        this.agentPromptMatcher = agentPromptMatcher;
        this.policyPromptSelector = policyPromptSelector;
        this.policyForceIncludeNonPolicyMode = policyForceIncludeNonPolicyMode;
    }

    @Override
    public PromptMatchOutcome match(PromptItemRecord item,
                                    PromptResolveContext context,
                                    Set<String> policyIncludes,
                                    Set<String> policyExcludes) {
        if (policyPromptSelector.containsAlias(policyExcludes, item.getKey())) {
            return PromptMatchOutcome.rejected("POLICY_EXCLUDED");
        }

        PromptAssemblyMode mode = PromptAssemblyMode.from(item.getAssemblyMode());
        boolean keyword = keywordPromptMatcher.matches(item, context == null ? "" : context.getUserInput());
        boolean agent = agentPromptMatcher.matches(item, context);
        boolean policy = policyPromptSelector.containsAlias(policyIncludes, item.getKey());
        boolean hasScope = agentPromptMatcher.hasScopeConstraint(item);
        boolean manual = policyPromptSelector.containsAlias(
                policyPromptSelector.toKeySet(context == null ? null : context.getManualPromptKeys()),
                item.getKey()
        );

        if (policyForceIncludeNonPolicyMode
                && policy
                && mode != PromptAssemblyMode.POLICY_ONLY
                && mode != PromptAssemblyMode.DISABLED) {
            return PromptMatchOutcome.matched("POLICY_ONLY", true);
        }

        if (requiresScope(mode) && !hasScope) {
            return PromptMatchOutcome.rejected("MISSING_MATCH_SCOPE");
        }

        if (alwaysPromptSelector.isAlways(mode)) {
            return PromptMatchOutcome.matched("ALWAYS");
        }

        return switch (mode) {
            case KEYWORD_ONLY -> {
                if (!keyword) {
                    yield PromptMatchOutcome.rejected("KEYWORD_NOT_MATCHED");
                }
                if (hasScope && !agent) {
                    yield PromptMatchOutcome.rejected("SCOPE_NOT_MATCHED");
                }
                yield PromptMatchOutcome.matched("KEYWORD_ONLY");
            }
            case AGENT_ONLY -> agent
                    ? PromptMatchOutcome.matched("AGENT_ONLY")
                    : PromptMatchOutcome.rejected("SCOPE_NOT_MATCHED");
            case KEYWORD_AND_AGENT -> keyword && agent
                    ? PromptMatchOutcome.matched("KEYWORD_AND_AGENT")
                    : PromptMatchOutcome.rejected(keyword ? "SCOPE_NOT_MATCHED" : "KEYWORD_NOT_MATCHED");
            case KEYWORD_OR_AGENT -> keyword || (hasScope && agent)
                    ? PromptMatchOutcome.matched("KEYWORD_OR_AGENT")
                    : PromptMatchOutcome.rejected("KEYWORD_OR_SCOPE_NOT_MATCHED");
            case POLICY_ONLY -> policy
                    ? PromptMatchOutcome.matched("POLICY_ONLY", true)
                    : PromptMatchOutcome.rejected("POLICY_NOT_INCLUDED");
            case MANUAL_ONLY -> manual
                    ? PromptMatchOutcome.matched("MANUAL_ONLY")
                    : PromptMatchOutcome.rejected("MANUAL_NOT_INCLUDED");
            case DISABLED -> PromptMatchOutcome.rejected("ASSEMBLY_DISABLED");
            case ALWAYS -> PromptMatchOutcome.matched("ALWAYS");
        };
    }

    private boolean requiresScope(PromptAssemblyMode mode) {
        return mode == PromptAssemblyMode.AGENT_ONLY
                || mode == PromptAssemblyMode.KEYWORD_AND_AGENT;
    }
}
