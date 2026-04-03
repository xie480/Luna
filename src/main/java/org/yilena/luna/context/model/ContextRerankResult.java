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
    List<Map<String, Object>> selectedPromptResources;
    List<String> selectedMemoryHints;
    List<List<String>> duplicateClusters;
    List<String> rejectedCandidates;
    Map<String, String> rationaleByNode;
}
