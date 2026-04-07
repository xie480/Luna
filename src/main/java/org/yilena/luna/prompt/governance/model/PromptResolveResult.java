package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class PromptResolveResult {
    List<ResolvedPromptItem> matchedItems;
    List<RejectedPromptItem> rejectedItems;
    Map<String, List<ResolvedPromptItem>> slotMapping;
    String policyId;
}
