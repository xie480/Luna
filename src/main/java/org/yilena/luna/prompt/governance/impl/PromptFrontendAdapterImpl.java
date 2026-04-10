package org.yilena.luna.prompt.governance.impl;

import org.springframework.stereotype.Component;
import org.yilena.luna.prompt.governance.PromptFrontendAdapter;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
/**
 * 提示词前端适配器实现，负责将提示词记录转换为便于前端展示与编辑的键值视图。
 */
public class PromptFrontendAdapterImpl implements PromptFrontendAdapter {

    @Override
    /**
     * 将指定分类下的提示词列表转换为分类-键值映射结构。
     */
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
