package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RejectedPromptItem {
    String key;
    String rejectedReason;
}
