package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

final class AgentPromptMatcher {
    private final AgentMatcher delegate;

    AgentPromptMatcher(AgentMatcher delegate) {
        this.delegate = delegate;
    }

    boolean hasScopeConstraint(PromptItemRecord item) {
        return delegate.hasScopeConstraint(item);
    }

    boolean matches(PromptItemRecord item, PromptResolveContext context) {
        return delegate.matches(item, context);
    }
}
