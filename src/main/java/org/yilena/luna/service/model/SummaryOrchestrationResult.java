package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.state.model.ContextState;

@Value
@Builder
public class SummaryOrchestrationResult {
    StructuredContextPackage contextPackage;
    SummaryResult summaryResult;
    ContextState contextState;
}
