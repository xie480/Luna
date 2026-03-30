package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Data;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

@Data
@Builder
public class OrchestrationDecision {
    private String sessionId;
    private TaskRuntimeState taskState;
    private RelationalRuntimeState relationalState;
    private StructuredContextPackage contextPackage;
}
