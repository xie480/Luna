package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.List;

final class AgentMatcher {

    boolean hasScopeConstraint(PromptItemRecord item) {
        MatchScope scope = item == null || item.getMatchScope() == null ? MatchScope.empty() : item.getMatchScope();
        return !safe(scope.getAgents()).isEmpty()
                || !safe(scope.getNodeKinds()).isEmpty()
                || !safe(scope.getTaskStates()).isEmpty()
                || !safe(scope.getModelFamilies()).isEmpty()
                || !safe(scope.getPersonaIds()).isEmpty()
                || !safe(scope.getSceneIds()).isEmpty();
    }

    boolean matches(PromptItemRecord item, PromptResolveContext context) {
        if (context == null || item == null) {
            return false;
        }
        MatchScope scope = item.getMatchScope() == null ? MatchScope.empty() : item.getMatchScope();
        if (!safe(scope.getAgents()).isEmpty() && !matchOne(scope.getAgents(), context.getAgent())) {
            return false;
        }
        if (!safe(scope.getNodeKinds()).isEmpty() && !matchOne(scope.getNodeKinds(), context.getNodeKind())) {
            return false;
        }
        if (!safe(scope.getTaskStates()).isEmpty() && !matchOne(scope.getTaskStates(), context.getTaskState())) {
            return false;
        }
        if (!safe(scope.getModelFamilies()).isEmpty() && !matchOne(scope.getModelFamilies(), context.getModelFamily())) {
            return false;
        }
        if (!safe(scope.getPersonaIds()).isEmpty() && !matchOne(scope.getPersonaIds(), context.getPersonaId())) {
            return false;
        }
        if (!safe(scope.getSceneIds()).isEmpty() && !matchOne(scope.getSceneIds(), context.getSceneId())) {
            return false;
        }
        return true;
    }

    private boolean matchOne(List<String> candidates, String value) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
