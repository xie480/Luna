package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.support.PromptKeyAliasSupport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PolicySelector {

    boolean containsAlias(Set<String> keys, String key) {
        if (keys == null || keys.isEmpty() || key == null || key.isBlank()) {
            return false;
        }
        for (String candidate : keys) {
            if (PromptKeyAliasSupport.matches(candidate, key)) {
                return true;
            }
        }
        return false;
    }

    Set<String> toKeySet(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                out.add(key.trim());
            }
        }
        return out;
    }
}
