package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class StateDrivenContextPipelineRequest {
    String sessionId;
    String triggerSource;
    RoundPipelineRequest roundPipelineRequest;
}
