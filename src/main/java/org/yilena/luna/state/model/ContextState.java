package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ContextState {
    String latestNarrativeSummary;
    Map<String, Object> latestStateSnapshot;
    List<String> activeKnowledgeRefs;
    List<String> activeMemoryRefs;
    List<String> activeToolEvidenceRefs;
    List<String> activeMcpPromptRefs;
    List<String> activeMcpResourceRefs;
    String latestContextSnapshotId;
}

