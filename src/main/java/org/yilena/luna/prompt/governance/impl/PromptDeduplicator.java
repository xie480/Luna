package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词去重器，负责按提示词键合并重复候选并保留优先级更高的版本。
 */
final class PromptDeduplicator {

    /**
     * 对解析结果按键去重，防止同一提示词被重复装配。
     */
    List<ResolvedPromptItem> deduplicate(List<ResolvedPromptItem> rows) {
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
}
