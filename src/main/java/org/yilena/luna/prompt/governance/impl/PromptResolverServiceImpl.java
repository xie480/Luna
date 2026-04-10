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
