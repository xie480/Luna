package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

@Data
public class PromptPolicyVersionSwitchRequest {
    private String policyId;
    private Long versionId;
}
