package org.yilena.luna.prompt.governance.impl;

import org.springframework.stereotype.Component;
import org.yilena.luna.prompt.governance.PromptFrontendAdapter;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PromptFrontendAdapterImpl implements PromptFrontendAdapter {

    @Override
    public Map<String, Object> toCategoryKeyValueView(String category, String subCategory, List<PromptItemRecord> rows) {
        Map<String, String> items = new LinkedHashMap<>();
        if (rows != null) {
            for (PromptItemRecord row : rows) {
                items.put(row.getKey(), row.getValue());
            }
        }
        return Map.of(
                "category", category == null ? "" : category,
                "subCategory", subCategory == null ? "" : subCategory,
                "items", items
        );
    }
}

