package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class RoundToolSemanticRequest {
    String sessionId;
    StructuredContextPackage contextPackage;
    TaskRuntimeState taskState;
    String explicitTaskGoal;
    String toolName;
    String toolDescription;
    List<Resource> executionCandidates;
    String toolContext;
    String stage;
    Map<String, Object> rawToolResultChannel;
}
