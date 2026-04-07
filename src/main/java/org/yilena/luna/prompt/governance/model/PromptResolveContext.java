package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PromptResolveContext {
    String sessionId;
    String userInput;
    String policyId;
    String personaId;
    String sceneId;
    String agent;
    String nodeKind;
    String taskState;
    String modelFamily;
    List<String> manualPromptKeys;
}
