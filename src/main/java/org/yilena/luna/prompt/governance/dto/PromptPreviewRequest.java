package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

import java.util.List;

@Data
public class PromptPreviewRequest {
    private String sessionId;
    private String userInput;
    private String policyId;
    private String personaId;
    private String sceneId;
    private String agent;
    private String nodeKind;
    private String taskState;
    private String modelFamily;
    private List<String> manualPromptKeys;
}
