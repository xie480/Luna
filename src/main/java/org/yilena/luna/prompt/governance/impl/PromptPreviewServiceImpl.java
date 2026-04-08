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
                "matchedItems", result.getMatchedItems(),
                "rejectedItems", result.getRejectedItems() == null ? List.of() : result.getRejectedItems()
        );
    }

    @Override
    public Map<String, Object> previewAssemble(PromptResolveContext context) {
        PromptResolveResult result = promptResolverService.resolve(context);
        Map<String, String> assembled = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResolvedPromptItem>> entry : result.getSlotMapping().entrySet()) {
            String text = PromptSectionAssemblerSupport.joinSlotValues(entry.getValue());
            assembled.put(entry.getKey(), text);
        }
        Map<String, List<String>> sectionMapping = PromptSectionAssemblerSupport.buildSectionPreview(result.getSlotMapping());
        Map<String, String> sectionAssembled = PromptSectionAssemblerSupport.toAssembledText(sectionMapping);
        return Map.of(
                "policyId", result.getPolicyId() == null ? "" : result.getPolicyId(),
                "matchedItems", result.getMatchedItems(),
                "rejectedItems", result.getRejectedItems() == null ? List.of() : result.getRejectedItems(),
                "slotMapping", result.getSlotMapping(),
                "assembled", assembled,
                "sectionMapping", sectionMapping,
                "sectionAssembled", sectionAssembled
        );
    }
}
