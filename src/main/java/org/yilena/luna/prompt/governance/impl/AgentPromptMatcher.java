package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

/**
 * 代理提示词匹配器，负责对外包装作用域匹配逻辑，
 * 让提示词解析流程以统一方式调用代理约束判断。
 */
final class AgentPromptMatcher {
    private final AgentMatcher delegate;

    AgentPromptMatcher(AgentMatcher delegate) {
        this.delegate = delegate;
    }

    /**
     * 判断提示词是否配置了代理或场景级作用域限制。
     */
    boolean hasScopeConstraint(PromptItemRecord item) {
        return delegate.hasScopeConstraint(item);
    }

    /**
     * 校验提示词是否命中当前代理解析上下文。
     */
    boolean matches(PromptItemRecord item, PromptResolveContext context) {
        return delegate.matches(item, context);
    }
}
