package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.entity.Resource;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class NodeWorksetResult {
    String mcpDrivenInput;
    String ragQuery;
    List<Map<String, Object>> mcpPreRankedCandidates;
    ContextRerankResult rerankResult;
    List<String> selectedKnowledgeSnippets;
    List<String> selectedMemorySnippets;
    List<String> selectedPreferenceSnippets;
    List<Resource> executionCandidates;
    List<String> mcpResourceHints;
}
