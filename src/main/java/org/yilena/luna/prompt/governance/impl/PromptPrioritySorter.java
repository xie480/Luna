package org.yilena.luna.prompt.governance.impl;

import org.yilena.luna.prompt.governance.model.PromptAssemblyMode;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 提示词优先级排序器，负责按装配阶段、优先级和键名稳定排序解析结果。
 */
final class PromptPrioritySorter {

    /**
     * 对解析后的提示词结果做稳定排序，确定最终装配顺序。
     */
    List<ResolvedPromptItem> sort(List<ResolvedPromptItem> rows) {
        List<ResolvedPromptItem> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt(this::assemblyStage)
                .thenComparing(Comparator.comparingInt((ResolvedPromptItem item) -> item.getPriority() == null ? 0 : item.getPriority()).reversed())
                .thenComparing(ResolvedPromptItem::getKey));
        return sorted;
    }

    private int assemblyStage(ResolvedPromptItem item) {
        if (item != null && "POLICY_ONLY".equalsIgnoreCase(item.getMatchReason())) {
            return 4;
        }
        return assemblyStageByAssemblyMode(item == null ? null : item.getAssemblyMode());
    }

    private int assemblyStageByAssemblyMode(String assemblyMode) {
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
}
