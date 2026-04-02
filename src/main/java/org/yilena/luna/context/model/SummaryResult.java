package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class SummaryResult {
    String narrativeSummary;
    Map<String, Object> stateSnapshot;
}

