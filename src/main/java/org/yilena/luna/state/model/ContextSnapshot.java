package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class ContextSnapshot {
    String snapshotId;
    String sessionId;
    Long planId;
    Long nodeId;
    Map<String, Object> payload;
}
