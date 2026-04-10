package org.yilena.luna.prompt.governance.impl;

import java.util.List;
import java.util.Set;

/**
 * 策略提示词选择器，负责处理策略包含/排除名单与手动指定提示词的别名匹配。
 */
final class PolicyPromptSelector {
    private final PolicySelector delegate;

    PolicyPromptSelector(PolicySelector delegate) {
        this.delegate = delegate;
    }

    /**
     * 判断给定提示词键是否命中策略集合中的任一别名。
     */
    boolean containsAlias(Set<String> keys, String key) {
        return delegate.containsAlias(keys, key);
    }

    /**
     * 将前端或配置传入的提示词键列表规整为可快速匹配的集合。
     */
    Set<String> toKeySet(List<String> keys) {
        return delegate.toKeySet(keys);
    }
}
