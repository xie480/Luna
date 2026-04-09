package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuntimeSlotMapper {

    Map<String, List<ResolvedPromptItem>> map(List<ResolvedPromptItem> items) {
        Map<String, List<ResolvedPromptItem>> slotMapping = new LinkedHashMap<>();
        for (ResolvedPromptItem item : items) {
            String slot = item.getRuntimeSlot() == null || item.getRuntimeSlot().isBlank() ? "runtime.prompt" : item.getRuntimeSlot();
            slotMapping.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(item);
        }
        return slotMapping;
    }
}
