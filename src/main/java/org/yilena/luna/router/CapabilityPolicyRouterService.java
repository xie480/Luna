package org.yilena.luna.router;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;

public interface CapabilityPolicyRouterService {

    List<Map<String, Object>> routeForContext(String sessionId,
                                              String query,
                                              TaskRuntimeState taskState,
                                              RelationalRuntimeState relationalState,
                                              int limit);

    List<Map<String, Object>> routeForExecution(String sessionId,
                                                String query,
                                                TaskRuntimeState taskState,
                                                RelationalRuntimeState relationalState,
                                                int limit);

    boolean shouldTriggerPlanOrchestration(String query, TaskRuntimeState taskState);
}

