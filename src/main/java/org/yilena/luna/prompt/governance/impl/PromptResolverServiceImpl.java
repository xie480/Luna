package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PromptResolverServiceImpl implements PromptResolverService {

    private final PromptRegistryService promptRegistryService;
    private final PromptPolicyService promptPolicyService;

    @Override
    public PromptResolveResult resolve(PromptResolveContext context) {
        PromptResolveContext ctx = context == null ? PromptResolveContext.builder().build() : context;
        Set<String> policyIncludes = promptPolicyService.resolveIncludedPromptKeys(ctx.getPolicyId());
        Set<String> policyExcludes = promptPolicyService.resolveExcludedPromptKeys(ctx.getPolicyId());
        List<ResolvedPromptItem> matched = new ArrayList<>();
        for (PromptItemRecord item : promptRegistryService.listAllActive()) {
            if (!item.isEnabled()) {
                continue;
            }
            String reason = matchReason(item, ctx, policyIncludes, policyExcludes);
            if (reason.isBlank()) {
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
                    .matchReason(reason)
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
                .comparingInt((ResolvedPromptItem item) -> assemblyStage(item.getAssemblyMode()))
                .thenComparing(Comparator.comparingInt((ResolvedPromptItem item) -> item.getPriority() == null ? 0 : item.getPriority()).reversed())
                .thenComparing(ResolvedPromptItem::getKey));
        Map<String, List<ResolvedPromptItem>> slotMapping = new LinkedHashMap<>();
        for (ResolvedPromptItem item : deduped) {
            String slot = item.getRuntimeSlot() == null || item.getRuntimeSlot().isBlank() ? "runtime.prompt" : item.getRuntimeSlot();
            slotMapping.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(item);
        }
        return PromptResolveResult.builder()
                .matchedItems(deduped)
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

    private String matchReason(PromptItemRecord item,
                               PromptResolveContext context,
                               Set<String> policyIncludes,
                               Set<String> policyExcludes) {
        if (policyExcludes.contains(item.getKey())) {
            return "";
        }
        PromptAssemblyMode mode = PromptAssemblyMode.from(item.getAssemblyMode());
        boolean keyword = keywordMatched(item, context == null ? "" : context.getUserInput());
        boolean agent = agentMatched(item, context);
        boolean policy = policyIncludes.contains(item.getKey());
        boolean manual = context != null
                && context.getManualPromptKeys() != null
                && context.getManualPromptKeys().stream().anyMatch(key -> key != null && key.equalsIgnoreCase(item.getKey()));
        return switch (mode) {
            case ALWAYS -> "ALWAYS";
            case KEYWORD_ONLY -> keyword ? "KEYWORD_ONLY" : "";
            case AGENT_ONLY -> agent ? "AGENT_ONLY" : "";
            case KEYWORD_AND_AGENT -> keyword && agent ? "KEYWORD_AND_AGENT" : "";
            case KEYWORD_OR_AGENT -> keyword || agent ? "KEYWORD_OR_AGENT" : "";
            case POLICY_ONLY -> policy ? "POLICY_ONLY" : "";
            case MANUAL_ONLY -> manual ? "MANUAL_ONLY" : "";
            case DISABLED -> "";
        };
    }

    private boolean keywordMatched(PromptItemRecord item, String userInput) {
        if (item.isHasTemplateVariables()) {
            return false;
        }
        if (!item.isKeywordMatchEnabled()) {
            return false;
        }
        if (item.getMatchKeywords() == null || item.getMatchKeywords().isEmpty()) {
            return false;
        }
        String input = userInput == null ? "" : userInput.toLowerCase();
        if (input.isBlank()) {
            return false;
        }
        for (String keyword : item.getMatchKeywords()) {
            if (keyword != null && !keyword.isBlank() && input.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean agentMatched(PromptItemRecord item, PromptResolveContext context) {
        if (context == null) {
            return false;
        }
        Map<String, Object> scope = item.getMatchScope() == null ? Map.of() : item.getMatchScope();
        List<String> agents = toList(scope.get("agents"));
        List<String> nodeKinds = toList(scope.get("nodeKinds"));
        List<String> taskStates = toList(scope.get("taskStates"));
        List<String> modelFamilies = toList(scope.get("modelFamilies"));
        List<String> personaIds = toList(scope.get("personaIds"));
        List<String> sceneIds = toList(scope.get("sceneIds"));
        if (!agents.isEmpty() && !matchOne(agents, context.getAgent())) {
            return false;
        }
        if (!nodeKinds.isEmpty() && !matchOne(nodeKinds, context.getNodeKind())) {
            return false;
        }
        if (!taskStates.isEmpty() && !matchOne(taskStates, context.getTaskState())) {
            return false;
        }
        if (!modelFamilies.isEmpty() && !matchOne(modelFamilies, context.getModelFamily())) {
            return false;
        }
        if (!personaIds.isEmpty() && !matchOne(personaIds, context.getPersonaId())) {
            return false;
        }
        if (!sceneIds.isEmpty() && !matchOne(sceneIds, context.getSceneId())) {
            return false;
        }
        return true;
    }

    private int assemblyStage(String assemblyMode) {
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

    @SuppressWarnings("unchecked")
    private List<String> toList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return out.stream().toList();
    }
}
