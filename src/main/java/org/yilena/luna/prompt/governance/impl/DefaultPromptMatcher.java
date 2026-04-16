package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.PromptMatcher;
import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptMatchOutcome;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;

import java.util.Set;

/**
 * 默认提示词匹配器，负责综合装配模式、关键字、代理作用域和策略白名单判断提示词是否应被装配。
 */
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

        // ... existing code ...

    /**
     * 计算单条提示词在当前上下文中的匹配结果，并返回命中或拒绝原因。
     * <p>
     * 该方法的主要流程包括：
     * 1. 检查策略排除列表，如果被排除则直接拒绝
     * 2. 计算多种匹配维度：关键词匹配、代理/作用域匹配、策略包含、手动指定
     * 3. 根据装配模式（AssemblyMode）应用不同的匹配规则
     * 4. 特殊处理策略强制包含、作用域缺失、ALWAYS模式等边界情况
     * 5. 返回匹配结果及原因，如果匹配成功还标记是否应用了策略
     * <p>
     * 支持的装配模式包括：
     * - KEYWORD_ONLY: 仅基于关键词匹配
     * - AGENT_ONLY: 仅基于代理/作用域匹配
     * - KEYWORD_AND_AGENT: 关键词和作用域都必须匹配
     * - KEYWORD_OR_AGENT: 关键词或作用域任一匹配即可
     * - POLICY_ONLY: 仅由策略控制，不受其他条件影响
     * - MANUAL_ONLY: 仅由手动指定控制
     * - ALWAYS: 始终匹配
     * - DISABLED: 禁用状态
     *
     * @param item            提示词项记录，包含key、assemblyMode、约束条件等信息
     * @param context         提示词解析上下文，包含用户输入、代理信息、手动指定键等
     * @param policyIncludes  策略包含的提示词键集合，用于POLICY_ONLY模式
     * @param policyExcludes  策略排除的提示词键集合，优先级最高
     * @return PromptMatchOutcome 匹配结果，包含：
     *         - matched: 是否匹配成功
     *         - rejectedReason: 如果拒绝，说明拒绝原因（如KEYWORD_NOT_MATCHED、SCOPE_NOT_MATCHED等）
     *         - matchReason: 如果匹配，说明匹配原因（如KEYWORD_ONLY、AGENT_ONLY等）
     *         - policyApplied: 是否应用了策略强制包含
     */
    @Override
    public PromptMatchOutcome match(PromptItemRecord item,
                                    PromptResolveContext context,
                                    Set<String> policyIncludes,
                                    Set<String> policyExcludes) {
        // 检查策略排除列表，被排除的提示词直接拒绝
        if (policyPromptSelector.containsAlias(policyExcludes, item.getKey())) {
            return PromptMatchOutcome.rejected("POLICY_EXCLUDED");
        }

        // 计算各维度的匹配结果
        PromptAssemblyMode mode = PromptAssemblyMode.from(item.getAssemblyMode());
        boolean keyword = keywordPromptMatcher.matches(item, context == null ? "" : context.getUserInput());
        boolean agent = agentPromptMatcher.matches(item, context);
        boolean policy = policyPromptSelector.containsAlias(policyIncludes, item.getKey());
        boolean hasScope = agentPromptMatcher.hasScopeConstraint(item);
        boolean manual = policyPromptSelector.containsAlias(
                policyPromptSelector.toKeySet(context == null ? null : context.getManualPromptKeys()),
                item.getKey()
        );

        /**
         * 先计算关键字命中、作用域命中、策略命中和手动指定结果，
         * 再依据装配模式给出最终决策。
         */

        // 处理策略强制包含非POLICY_ONLY模式的特殊情况
        if (policyForceIncludeNonPolicyMode
                && policy
                && mode != PromptAssemblyMode.POLICY_ONLY
                && mode != PromptAssemblyMode.DISABLED) {
            return PromptMatchOutcome.matched("POLICY_ONLY", true);
        }

        // 检查是否需要作用域约束，如果需要但缺失则拒绝
        if (requiresScope(mode) && !hasScope) {
            return PromptMatchOutcome.rejected("MISSING_MATCH_SCOPE");
        }

        // ALWAYS模式直接匹配，不受其他条件影响
        if (alwaysPromptSelector.isAlways(mode)) {
            return PromptMatchOutcome.matched("ALWAYS");
        }

        // 根据装配模式应用不同的匹配规则
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

    // ... existing code ...


    private boolean requiresScope(PromptAssemblyMode mode) {
        return mode == PromptAssemblyMode.AGENT_ONLY
                || mode == PromptAssemblyMode.KEYWORD_AND_AGENT;
    }
}
