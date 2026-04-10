package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.support.PromptKeyAliasSupport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 策略键匹配器，负责统一处理提示词键及其别名的匹配和集合规整。
 */
final class PolicySelector {

    /**
     * 判断目标提示词键是否被策略集合中的任一别名覆盖。
     */
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

    /**
     * 清洗原始键列表，生成去重后的策略键集合。
     */
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
