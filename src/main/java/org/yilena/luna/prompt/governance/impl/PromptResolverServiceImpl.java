package org.yilena.luna.prompt.governance.impl;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.RejectedPromptItem;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PromptResolverServiceImpl implements PromptResolverService {

    private final PromptRegistryService promptRegistryService;
    private final PromptPolicyService promptPolicyService;
    private final PromptCategoryService promptCategoryService;
    private final KeywordMatcher keywordMatcher;
    private final AgentMatcher agentMatcher;
    private final PolicySelector policySelector;

    @Autowired
    public PromptResolverServiceImpl(PromptRegistryService promptRegistryService,
                                     PromptPolicyService promptPolicyService,
                                     PromptCategoryService promptCategoryService) {
        this(promptRegistryService, promptPolicyService, promptCategoryService,
                new KeywordMatcher(), new AgentMatcher(), new PolicySelector());
    }

    PromptResolverServiceImpl(PromptRegistryService promptRegistryService,
                              PromptPolicyService promptPolicyService,
                              PromptCategoryService promptCategoryService,
                              KeywordMatcher keywordMatcher,
                              AgentMatcher agentMatcher,
                              PolicySelector policySelector) {
        this.promptRegistryService = promptRegistryService;
        this.promptPolicyService = promptPolicyService;
        this.promptCategoryService = promptCategoryService;
        this.keywordMatcher = keywordMatcher;
        this.agentMatcher = agentMatcher;
        this.policySelector = policySelector;
    }

    @Override
    public PromptResolveResult resolve(PromptResolveContext context) {
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
            MatchDecision decision = matchDecision(item, ctx, policyIncludes, policyExcludes);
            if (!decision.matched()) {
                rejected.add(RejectedPromptItem.builder()
                        .key(item.getKey())
                        .rejectedReason(decision.rejectedReason())
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
                    .matchReason(decision.matchReason())
                    .policyApplied(decision.policyApplied())
                    .hasTemplateVariables(item.isHasTemplateVariables())
                    .keywordMatchEnabled(item.isKeywordMatchEnabled())
                    .priority(item.getPriority())
                    .version(item.getVersion())
                    .versionLabel(item.getVersionLabel())
                    .assemblerVersion("assembler.v1")
                    .build());
        }
        List<ResolvedPromptItem> deduped = dedupe(matched);
        deduped.sort(Comparator
                .comparingInt(this::assemblyStage)
                .thenComparing(Comparator.comparingInt((ResolvedPromptItem item) -> item.getPriority() == null ? 0 : item.getPriority()).reversed())
                .thenComparing(ResolvedPromptItem::getKey));
        Map<String, List<ResolvedPromptItem>> slotMapping = new LinkedHashMap<>();
        for (ResolvedPromptItem item : deduped) {
            String slot = item.getRuntimeSlot() == null || item.getRuntimeSlot().isBlank() ? "runtime.prompt" : item.getRuntimeSlot();
            slotMapping.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(item);
        }
        return PromptResolveResult.builder()
                .matchedItems(deduped)
                .rejectedItems(rejected)
                .slotMapping(slotMapping)
                .policyId(ctx.getPolicyId())
                .build();
    }

    private List<ResolvedPromptItem> dedupe(List<ResolvedPromptItem> rows) {
        Map<String, ResolvedPromptItem> dedup = new LinkedHashMap<>();
        for (ResolvedPromptItem row : rows) {
            ResolvedPromptItem existing = dedup.get(row.getKey());
            if (existing == null) {
                dedup.put(row.getKey(), row);
                continue;
            }
            int currentPriority = row.getPriority() == null ? 0 : row.getPriority();
            int existingPriority = existing.getPriority() == null ? 0 : existing.getPriority();
            if (currentPriority > existingPriority) {
                dedup.put(row.getKey(), row);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private MatchDecision matchDecision(PromptItemRecord item,
                                        PromptResolveContext context,
                                        Set<String> policyIncludes,
                                        Set<String> policyExcludes) {
        if (policySelector.containsAlias(policyExcludes, item.getKey())) {
            return MatchDecision.rejected("POLICY_EXCLUDED");
        }
        PromptAssemblyMode mode = PromptAssemblyMode.from(item.getAssemblyMode());
        boolean keyword = keywordMatcher.matches(item, context == null ? "" : context.getUserInput(), promptCategoryService);
        boolean agent = agentMatcher.matches(item, context);
        boolean policy = policySelector.containsAlias(policyIncludes, item.getKey());
        boolean hasScope = agentMatcher.hasScopeConstraint(item);
        boolean manual = policySelector.containsAlias(
                policySelector.toKeySet(context == null ? null : context.getManualPromptKeys()),
                item.getKey());

        if (policy && mode != PromptAssemblyMode.POLICY_ONLY && mode != PromptAssemblyMode.DISABLED) {
            return MatchDecision.matched("POLICY_ONLY", true);
        }

        if (requiresScope(mode) && !hasScope) {
            return MatchDecision.rejected("MISSING_MATCH_SCOPE");
        }

        return switch (mode) {
            case ALWAYS -> MatchDecision.matched("ALWAYS");
            case KEYWORD_ONLY -> {
                if (!keyword) {
                    yield MatchDecision.rejected("KEYWORD_NOT_MATCHED");
                }
                if (hasScope && !agent) {
                    yield MatchDecision.rejected("SCOPE_NOT_MATCHED");
                }
                yield MatchDecision.matched("KEYWORD_ONLY");
            }
            case AGENT_ONLY -> agent
                    ? MatchDecision.matched("AGENT_ONLY")
                    : MatchDecision.rejected("SCOPE_NOT_MATCHED");
            case KEYWORD_AND_AGENT -> keyword && agent
                    ? MatchDecision.matched("KEYWORD_AND_AGENT")
                    : MatchDecision.rejected(keyword ? "SCOPE_NOT_MATCHED" : "KEYWORD_NOT_MATCHED");
            case KEYWORD_OR_AGENT -> keyword || (hasScope && agent)
                    ? MatchDecision.matched("KEYWORD_OR_AGENT")
                    : MatchDecision.rejected("KEYWORD_OR_SCOPE_NOT_MATCHED");
            case POLICY_ONLY -> policy
                    ? MatchDecision.matched("POLICY_ONLY", true)
                    : MatchDecision.rejected("POLICY_NOT_INCLUDED");
            case MANUAL_ONLY -> manual
                    ? MatchDecision.matched("MANUAL_ONLY")
                    : MatchDecision.rejected("MANUAL_NOT_INCLUDED");
            case DISABLED -> MatchDecision.rejected("ASSEMBLY_DISABLED");
        };
    }

    private boolean requiresScope(PromptAssemblyMode mode) {
        return mode == PromptAssemblyMode.AGENT_ONLY
                || mode == PromptAssemblyMode.KEYWORD_AND_AGENT;
    }

    private int assemblyStage(ResolvedPromptItem item) {
        if (item != null && "POLICY_ONLY".equalsIgnoreCase(item.getMatchReason())) {
            return 4;
        }
        return assemblyStageByAssemblyMode(item == null ? null : item.getAssemblyMode());
    }

    private int assemblyStageByAssemblyMode(String assemblyMode) {
        PromptAssemblyMode mode = PromptAssemblyMode.from(assemblyMode);
        return switch (mode) {
            case ALWAYS -> 1;
            case AGENT_ONLY -> 2;
            case KEYWORD_ONLY -> 3;
            case POLICY_ONLY -> 4;
            case KEYWORD_AND_AGENT, KEYWORD_OR_AGENT -> 5;
            case MANUAL_ONLY -> 6;
            case DISABLED -> 7;
        };
    }

    private record MatchDecision(boolean matched, String matchReason, String rejectedReason, boolean policyApplied) {
        private static MatchDecision matched(String reason) {
            return new MatchDecision(true, reason, "", false);
        }

        private static MatchDecision matched(String reason, boolean policyApplied) {
            return new MatchDecision(true, reason, "", policyApplied);
        }

        private static MatchDecision rejected(String reason) {
            return new MatchDecision(false, "", reason, false);
        }
    }
}
