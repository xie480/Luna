package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

import java.util.List;

@Data
public class PromptPolicySaveRequest {
    private String policyId;
    private String policyName;
    private String description;
    private Boolean enabled;
    private List<String> includePromptKeys;
    private List<String> excludePromptKeys;
    private String version;
    private String changeNote;
}
