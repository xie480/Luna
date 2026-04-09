package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PromptDeduplicator {

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
