package org.yilena.luna.prompt.governance.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptMatcher;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptMatchOutcome;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.RejectedPromptItem;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Prompt 解析服务实现，负责按策略、匹配器和优先级从注册中心筛选出最终可装配的 Prompt 集合。
 */
@Service
public class PromptResolverServiceImpl implements PromptResolverService {

    private final PromptRegistryService promptRegistryService;
    private final PromptPolicyService promptPolicyService;
    private final PromptMatcher promptMatcher;
    private final PromptDeduplicator promptDeduplicator;
    private final PromptPrioritySorter promptPrioritySorter;
    private final RuntimeSlotMapper runtimeSlotMapper;
    @Value("${prompt.governance.assembler-version:assembler.v1}")
    private String assemblerVersion = "assembler.v1";

    @Autowired
    public PromptResolverServiceImpl(PromptRegistryService promptRegistryService,
                                     PromptPolicyService promptPolicyService,
                                     PromptCategoryService promptCategoryService,
                                     @Value("${prompt.governance.policy-force-include-non-policy-mode:true}")
                                     boolean policyForceIncludeNonPolicyMode) {
        this(promptRegistryService, promptPolicyService, promptCategoryService,
                defaultPromptMatcher(promptCategoryService, policyForceIncludeNonPolicyMode),
                new PromptDeduplicator(),
                new PromptPrioritySorter(),
                new RuntimeSlotMapper());
    }

    public PromptResolverServiceImpl(PromptRegistryService promptRegistryService,
                                     PromptPolicyService promptPolicyService,
                                     PromptCategoryService promptCategoryService) {
        this(promptRegistryService, promptPolicyService, promptCategoryService,
                defaultPromptMatcher(promptCategoryService, true),
                new PromptDeduplicator(),
                new PromptPrioritySorter(),
                new RuntimeSlotMapper());
    }

    PromptResolverServiceImpl(PromptRegistryService promptRegistryService,
                              PromptPolicyService promptPolicyService,
                              PromptCategoryService promptCategoryService,
                              PromptMatcher promptMatcher,
                              PromptDeduplicator promptDeduplicator,
                              PromptPrioritySorter promptPrioritySorter,
                              RuntimeSlotMapper runtimeSlotMapper) {
        this.promptRegistryService = promptRegistryService;
        this.promptPolicyService = promptPolicyService;
        this.promptMatcher = promptMatcher;
        this.promptDeduplicator = promptDeduplicator;
        this.promptPrioritySorter = promptPrioritySorter;
        this.runtimeSlotMapper = runtimeSlotMapper;
    }

    // ... existing code ...

    /**
     * 解析提示词，根据上下文和策略匹配活跃的提示词项并生成可装配结果。
     * <p>
     * 该方法的主要流程包括：
     * 1. 从策略服务获取包含和排除的提示词键集合
     * 2. 遍历所有活跃的提示词项，执行匹配逻辑
     * 3. 将未启用或不匹配的提示词加入拒绝列表，记录拒绝原因
     * 4. 将匹配的提示词构建为ResolvedPromptItem，包含版本、优先级、槽位等元信息
     * 5. 对匹配结果进行去重、按优先级排序
     * 6. 映射运行时槽位，生成最终的槽位映射关系
     * <p>
     * 匹配过程受策略控制，policyIncludes和policyExcludes决定哪些提示词可以被选中。
     * 最终结果包含匹配的提示词列表、拒绝的提示词列表和槽位映射，供后续组装使用。
     *
     * @param context 提示词解析上下文，包含：
     *                - policyId: 策略ID，用于确定包含/排除规则
     *                - userInput: 用户输入，用于关键词匹配
     *                - sessionId: 会话ID，用于上下文关联
     *                - personaId/sceneId: 角色ID和场景ID，用于精细化匹配
     *                - agent/nodeKind/taskState: 代理、节点类型、任务状态，用于场景匹配
     *                - modelFamily: 模型家族，用于模型特定的提示词选择
     * @return PromptResolveResult 提示词解析结果，包含：
     *         - matchedItems: 匹配成功的提示词列表（已去重、按优先级排序）
     *         - rejectedItems: 被拒绝的提示词列表及拒绝原因
     *         - slotMapping: 运行时槽位映射关系（变量名到值的映射）
     *         - policyId: 应用的策略ID
     */
    @Override
    public PromptResolveResult resolve(PromptResolveContext context) {
        /**
         * 先解析策略包含与排除集合，再逐条对活跃 Prompt 执行匹配，收集命中和拒绝原因。
         */
        PromptResolveContext ctx = context == null ? PromptResolveContext.builder().build() : context;
        Set<String> policyIncludes = promptPolicyService.resolveIncludedPromptKeys(ctx.getPolicyId());
        Set<String> policyExcludes = promptPolicyService.resolveExcludedPromptKeys(ctx.getPolicyId());
        List<ResolvedPromptItem> matched = new ArrayList<>();
        List<RejectedPromptItem> rejected = new ArrayList<>();

        // 遍历所有活跃的提示词项，执行匹配逻辑并分类收集结果
        for (PromptItemRecord item : promptRegistryService.listAllActive()) {
            if (!item.isEnabled()) {
                rejected.add(RejectedPromptItem.builder()
                        .key(item.getKey())
                        .rejectedReason("ITEM_DISABLED")
                        .build());
                continue;
            }
            PromptMatchOutcome decision = promptMatcher.match(item, ctx, policyIncludes, policyExcludes);
            if (!decision.isMatched()) {
                rejected.add(RejectedPromptItem.builder()
                        .key(item.getKey())
                        .rejectedReason(decision.getRejectedReason())
                        .build());
                continue;
            }
            matched.add(ResolvedPromptItem.builder()
                    .itemId(item.getItemId())
                    .versionId(item.getVersionId())
                    .key(item.getKey())
                    .name(item.getName())
                    .value(item.getValue())
                    .category(item.getCategory())
                    .subCategory(item.getSubCategory())
                    .description(item.getDescription())
                    .runtimeSlot(item.getRuntimeSlot())
                    .assemblyMode(item.getAssemblyMode())
                    .matchReason(decision.getMatchReason())
                    .policyApplied(decision.isPolicyApplied())
                    .hasTemplateVariables(item.isHasTemplateVariables())
                    .keywordMatchEnabled(item.isKeywordMatchEnabled())
                    .priority(item.getPriority())
                    .version(item.getVersion())
                    .versionLabel(item.getVersionLabel())
                    .assemblerVersion(resolveAssemblerVersion())
                    .build());
        }

        /**
         * 匹配完成后依次做去重、优先级排序和运行时槽位映射，形成最终可装配结果。
         */
        List<ResolvedPromptItem> deduped = promptDeduplicator.deduplicate(matched);
        List<ResolvedPromptItem> sorted = promptPrioritySorter.sort(deduped);
        return PromptResolveResult.builder()
                .matchedItems(sorted)
                .rejectedItems(rejected)
                .slotMapping(runtimeSlotMapper.map(sorted))
                .policyId(ctx.getPolicyId())
                .build();
    }

    // ... existing code ...


    private String resolveAssemblerVersion() {
        if (assemblerVersion == null || assemblerVersion.isBlank()) {
            return "assembler.v1";
        }
        return assemblerVersion;
    }

    private static PromptMatcher defaultPromptMatcher(PromptCategoryService promptCategoryService,
                                                      boolean policyForceIncludeNonPolicyMode) {
        /**
         * 默认匹配器链路按永远匹配、关键词匹配、Agent 匹配和策略选择器组合，覆盖主要治理场景。
         */
        return new DefaultPromptMatcher(
                new AlwaysPromptSelector(),
                new KeywordPromptMatcher(new KeywordMatcher(), promptCategoryService),
                new AgentPromptMatcher(new AgentMatcher()),
                new PolicyPromptSelector(new PolicySelector()),
                policyForceIncludeNonPolicyMode
        );
    }
}
