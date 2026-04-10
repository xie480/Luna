package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时槽位映射器，负责将解析后的提示词按运行时槽位分组，
 * 便于后续装配到不同上下文分区。
 */
final class RuntimeSlotMapper {

    /**
     * 将提示词结果按运行时槽位归组，缺省槽位统一回落到主运行时提示词。
     */
    Map<String, List<ResolvedPromptItem>> map(List<ResolvedPromptItem> items) {
        Map<String, List<ResolvedPromptItem>> slotMapping = new LinkedHashMap<>();
        for (ResolvedPromptItem item : items) {
            String slot = item.getRuntimeSlot() == null || item.getRuntimeSlot().isBlank() ? "runtime.prompt" : item.getRuntimeSlot();
            slotMapping.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(item);
        }
        return slotMapping;
    }
}
