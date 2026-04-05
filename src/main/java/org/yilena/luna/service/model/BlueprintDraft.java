package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class BlueprintDraft {
    String explicitTaskGoal;
    String currentStage;
    String currentNode;
    Map<String, Object> taskStateSnapshot;
    List<Map<String, Object>> workflowHints;
    List<Map<String, Object>> evidenceBlocks;
    Map<String, Object> rationaleByNode;
}

