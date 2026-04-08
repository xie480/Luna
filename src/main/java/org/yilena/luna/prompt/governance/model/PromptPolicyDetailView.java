package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PromptPolicyDetailView {
    Long id;
    String policyId;
    String policyName;
    String description;
    boolean enabled;
    Long currentVersionId;
    String currentVersionNo;
    List<String> includePromptKeys;
    List<String> excludePromptKeys;
}
