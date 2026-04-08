package org.yilena.luna.prompt.governance.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptPolicy {
    private String policyId;
    private String name;
    private String description;
    private List<String> includeItems;
    private Boolean enabled;
}
