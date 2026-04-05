package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ContextRerankResult {
    List<EvidenceBlock> selectedKnowledgeEvidenceBlocks;
    List<String> selectedKnowledgeBlocks;
    List<Map<String, Object>> selectedToolCandidates;
    List<Map<String, Object>> selectedPromptCandidates;
    List<Map<String, Object>> selectedResourceCandidates;
    List<Map<String, Object>> selectedWorkflowCandidates;
    List<String> selectedMemoryHints;
    List<List<String>> duplicateClusters;
    List<String> rejectedCandidates;
    Map<String, String> rationaleByNode;

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
