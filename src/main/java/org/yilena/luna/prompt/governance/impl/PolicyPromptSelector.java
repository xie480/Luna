package org.yilena.luna.prompt.governance.impl;

import java.util.List;
import java.util.Set;

final class PolicyPromptSelector {
    private final PolicySelector delegate;

    PolicyPromptSelector(PolicySelector delegate) {
        this.delegate = delegate;
    }

    boolean containsAlias(Set<String> keys, String key) {
        return delegate.containsAlias(keys, key);
    }

    Set<String> toKeySet(List<String> keys) {
        return delegate.toKeySet(keys);
    }
}
