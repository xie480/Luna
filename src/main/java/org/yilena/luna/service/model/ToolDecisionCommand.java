package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;

@Value
@Builder
public class ToolDecisionCommand {
    String sessionId;
    String rawUserInput;
    String toolDecisionInput;
    String policyId;
    String personaId;
    String sceneId;
    TaskRuntimeState taskState;
    RelationalRuntimeState relationalState;
    String modelFamily;
    List<Resource> executionCandidates;
    String governedInputSignature;
    String assembledDecisionContext;
}
