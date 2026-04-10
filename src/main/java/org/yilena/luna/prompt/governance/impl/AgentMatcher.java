package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.List;

/**
 * 提示词作用域匹配器，负责根据代理、节点类型、任务状态等运行上下文判断提示词是否生效。
 */
final class AgentMatcher {

    /**
     * 判断当前提示词是否声明了作用域约束。
     */
    boolean hasScopeConstraint(PromptItemRecord item) {
        MatchScope scope = item == null || item.getMatchScope() == null ? MatchScope.empty() : item.getMatchScope();
        return !safe(scope.getAgents()).isEmpty()
                || !safe(scope.getNodeKinds()).isEmpty()
                || !safe(scope.getTaskStates()).isEmpty()
                || !safe(scope.getModelFamilies()).isEmpty()
                || !safe(scope.getPersonaIds()).isEmpty()
                || !safe(scope.getSceneIds()).isEmpty();
    }

    /**
     * 按作用域约束校验提示词是否匹配当前解析上下文。
     */
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
