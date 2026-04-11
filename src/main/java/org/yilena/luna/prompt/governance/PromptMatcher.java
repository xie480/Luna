package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptMatchOutcome;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.Set;

/**
 * 提示词匹配器接口，负责根据解析上下文、策略包含排除集合判断单条提示词是否命中，
 * 是提示词治理链路中的核心筛选组件。
 */
public interface PromptMatcher {
    PromptMatchOutcome match(PromptItemRecord item,
                             PromptResolveContext context,
                             Set<String> policyIncludes,
                             Set<String> policyExcludes);
}
