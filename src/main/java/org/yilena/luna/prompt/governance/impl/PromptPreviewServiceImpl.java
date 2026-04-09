package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptPreviewService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;
import org.yilena.luna.prompt.governance.support.PromptSectionAssemblerSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PromptPreviewServiceImpl implements PromptPreviewService {

    private final PromptResolverService promptResolverService;

    @Override
    public Map<String, Object> previewMatch(PromptResolveContext context) {
        PromptResolveResult result = promptResolverService.resolve(context);
        return Map.of(
                "policyId", result.getPolicyId() == null ? "" : result.getPolicyId(),
                "matchedItems", toPreviewMatchedItems(result.getMatchedItems()),
                "rejectedItems", result.getRejectedItems() == null ? List.of() : result.getRejectedItems()
        );
    }

    @Override
    public Map<String, Object> previewAssemble(PromptResolveContext context) {
        PromptResolveResult result = promptResolverService.resolve(context);
        Map<String, List<ResolvedPromptItem>> slotMapping = result.getSlotMapping() == null ? Map.of() : result.getSlotMapping();
        Map<String, String> assembled = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResolvedPromptItem>> entry : slotMapping.entrySet()) {
            String text = PromptSectionAssemblerSupport.joinSlotValues(entry.getValue());
            assembled.put(entry.getKey(), text);
        }
        Map<String, List<String>> sectionMapping = PromptSectionAssemblerSupport.buildSectionPreview(slotMapping);
        Map<String, String> sectionAssembled = PromptSectionAssemblerSupport.toAssembledText(sectionMapping);
        PromptSectionAssemblerSupport.PromptRefFilterResult finalFilter = PromptSectionAssemblerSupport.filterPromptRefsByFinalSections(
                toPromptRefRows(result.getMatchedItems()),
                toPromptRefSlotMapping(slotMapping),
                sectionMapping,
                sectionMapping
        );
        return Map.of(
                "policyId", result.getPolicyId() == null ? "" : result.getPolicyId(),
                "matchedItems", toPreviewMatchedItems(result.getMatchedItems()),
                "rejectedItems", result.getRejectedItems() == null ? List.of() : result.getRejectedItems(),
                "slotMapping", slotMapping,
                "assembled", assembled,
                "sectionMapping", sectionMapping,
                "sectionAssembled", sectionAssembled,
                "filteredOutItems", finalFilter.filteredOutItems()
        );
    }

    private List<Map<String, Object>> toPreviewMatchedItems(List<ResolvedPromptItem> matchedItems) {
        if (matchedItems == null || matchedItems.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (ResolvedPromptItem item : matchedItems) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getItemId());
            row.put("versionId", item.getVersionId());
            row.put("key", item.getKey());
            row.put("name", item.getName());
            row.put("value", item.getValue());
            row.put("category", item.getCategory());
            row.put("subCategory", item.getSubCategory());
            row.put("description", item.getDescription());
            row.put("runtimeSlot", item.getRuntimeSlot());
            row.put("assemblyMode", item.getAssemblyMode());
            row.put("matchReason", item.getMatchReason());
            row.put("reason", item.getMatchReason());
            row.put("policyApplied", item.isPolicyApplied());
            row.put("hasTemplateVariables", item.isHasTemplateVariables());
            row.put("keywordMatchEnabled", item.isKeywordMatchEnabled());
            row.put("priority", item.getPriority());
            row.put("version", item.getVersion());
            row.put("versionLabel", item.getVersionLabel());
            row.put("assemblerVersion", item.getAssemblerVersion());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> toPromptRefRows(List<ResolvedPromptItem> matchedItems) {
        if (matchedItems == null || matchedItems.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (ResolvedPromptItem item : matchedItems) {
            if (item == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getItemId());
            row.put("versionId", item.getVersionId());
            row.put("key", item.getKey());
            row.put("version", item.getVersion());
            row.put("runtimeSlot", item.getRuntimeSlot());
            row.put("matchReason", item.getMatchReason());
            row.put("category", item.getCategory());
            row.put("value", item.getValue());
            rows.add(PromptSectionAssemblerSupport.withPromptRefAliases(row));
        }
        return rows;
    }

    private Map<String, List<Map<String, Object>>> toPromptRefSlotMapping(Map<String, List<ResolvedPromptItem>> slotMapping) {
        if (slotMapping == null || slotMapping.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> rowsBySlot = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResolvedPromptItem>> entry : slotMapping.entrySet()) {
            String slot = entry.getKey() == null ? "" : entry.getKey();
            if (slot.isBlank()) {
                continue;
            }
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            List<ResolvedPromptItem> items = entry.getValue() == null ? List.of() : entry.getValue();
            for (ResolvedPromptItem item : items) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("itemId", item.getItemId());
                row.put("versionId", item.getVersionId());
                row.put("key", item.getKey());
                row.put("version", item.getVersion());
                row.put("runtimeSlot", item.getRuntimeSlot());
                row.put("matchReason", item.getMatchReason());
                row.put("category", item.getCategory());
                row.put("value", item.getValue());
                rows.add(PromptSectionAssemblerSupport.withPromptRefAliases(row));
            }
            rowsBySlot.put(slot, rows);
        }
        return rowsBySlot;
    }
}
