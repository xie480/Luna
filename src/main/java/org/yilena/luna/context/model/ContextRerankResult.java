package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载上下文重排结果，记录被保留的证据块、候选能力和筛选理由。
 */
@Value
@Builder
public class ContextRerankResult {
    /**
     * 最终选中的知识证据块对象。
     */
    List<EvidenceBlock> selectedKnowledgeEvidenceBlocks;
    /**
     * 最终选中的知识文本块。
     */
    List<String> selectedKnowledgeBlocks;
    /**
     * 最终选中的工具候选集合。
     */
    List<Map<String, Object>> selectedToolCandidates;
    /**
     * 最终选中的提示词候选集合。
     */
    List<Map<String, Object>> selectedPromptCandidates;
    /**
     * 最终选中的资源候选集合。
     */
    List<Map<String, Object>> selectedResourceCandidates;
    /**
     * 最终选中的工作流候选集合。
     */
    List<Map<String, Object>> selectedWorkflowCandidates;
    /**
     * 最终保留的记忆提示集合。
     */
    List<String> selectedMemoryHints;
    /**
     * 被识别为重复的候选聚类结果。
     */
    List<List<String>> duplicateClusters;
    /**
     * 被淘汰的候选说明。
     */
    List<String> rejectedCandidates;
    /**
     * 各节点对应的筛选理由。
     */
    Map<String, String> rationaleByNode;

    /**
     * 将提示词、资源和工作流候选合并为统一的提示资源集合，便于后续统一消费。
     */
    public List<Map<String, Object>> getSelectedPromptResources() {
        List<Map<String, Object>> merged = new java.util.ArrayList<>();
        if (selectedPromptCandidates != null) {
            merged.addAll(selectedPromptCandidates);
        }
        if (selectedResourceCandidates != null) {
            merged.addAll(selectedResourceCandidates);
        }
        if (selectedWorkflowCandidates != null) {
            merged.addAll(selectedWorkflowCandidates);
        }
        return merged;
    }
}
