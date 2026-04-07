package org.yilena.luna.prompt.governance.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.prompt.governance.PromptQueryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PromptQueryServiceImpl implements PromptQueryService {

    private final PromptRegistryService promptRegistryService;

    @Override
    public List<String> listCategories() {
        return promptRegistryService.listCategories();
    }

    @Override
    public Map<String, String> listKeyValueByCategory(String category, String subCategory) {
        Map<String, String> out = new LinkedHashMap<>();
        for (PromptItemRecord item : promptRegistryService.listByCategory(category, subCategory)) {
            out.put(item.getKey(), item.getValue());
        }
        return out;
    }

    @Override
    public List<PromptItemRecord> search(PromptSearchRequest request) {
        PromptSearchRequest effective = request == null ? new PromptSearchRequest() : request;
        long pageNo = effective.getPageNo() == null || effective.getPageNo() <= 0 ? 1L : effective.getPageNo();
        long pageSize = effective.getPageSize() == null || effective.getPageSize() <= 0 ? 20L : Math.min(200L, effective.getPageSize());
        List<PromptItemRecord> filtered = promptRegistryService.listAllActive().stream()
                .filter(item -> matchText(item.getCategory(), effective.getCategory()))
                .filter(item -> matchText(item.getSubCategory(), effective.getSubCategory()))
                .filter(item -> containsText(item.getKey(), effective.getKeyLike()))
                .filter(item -> containsText(item.getValue(), effective.getValueLike()))
                .filter(item -> effective.getHasTemplateVariables() == null || item.isHasTemplateVariables() == effective.getHasTemplateVariables())
                .filter(item -> effective.getKeywordMatchEnabled() == null || item.isKeywordMatchEnabled() == effective.getKeywordMatchEnabled())
                .filter(item -> matchText(item.getAssemblyMode(), effective.getAssemblyMode()))
                .filter(item -> effective.getEnabled() == null || item.isEnabled() == effective.getEnabled())
                .toList();
        int start = (int) ((pageNo - 1) * pageSize);
        if (start >= filtered.size()) {
            return List.of();
        }
        int end = (int) Math.min(filtered.size(), start + pageSize);
        return filtered.subList(start, end);
    }

    @Override
    public Optional<PromptItemRecord> detailByKey(String key) {
        return promptRegistryService.getByKey(key);
    }

    private boolean matchText(String value, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return value != null && value.equalsIgnoreCase(expected.trim());
    }

    private boolean containsText(String value, String like) {
        if (like == null || like.isBlank()) {
            return true;
        }
        return value != null && value.toLowerCase().contains(like.trim().toLowerCase());
    }
}

