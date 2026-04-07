package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
public class PromptVersionSwitchRequest {
    private Long versionId;
    private String key;
}

