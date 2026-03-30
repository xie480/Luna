package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Data;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class StructuredContextPackage {
    private String sessionId;
    private TaskRuntimeState taskState;
    private RelationalRuntimeState relationalState;
    private Map<String, Object> runtime;
    private Map<String, Object> taskContext;
    private Map<String, Object> relationalContext;
    private List<Map<String, Object>> recentMessages;
    private List<Map<String, Object>> capabilityCandidates;
    private Map<String, Object> promptPolicy;
    private Map<String, Integer> tokenBudgetPlan;
}
