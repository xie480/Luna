package org.yilena.luna.router;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;
import java.util.Map;

/**
 * 该服务接口负责根据任务状态和权限策略路由能力候选，为上下文构建和执行阶段筛选可用能力。
 */
public interface CapabilityPolicyRouterService {

    /**
     * 为上下文构建阶段筛选能力候选，优先返回适合当前任务理解与规划的资源。
     */
    List<Map<String, Object>> routeForContext(String sessionId,
                                              String query,
                                              TaskRuntimeState taskState,
                                              RelationalRuntimeState relationalState,
                                              int limit);

    /**
     * 为执行阶段筛选能力候选，优先返回适合直接调用的工具、工作流或资源。
     */
    List<Map<String, Object>> routeForExecution(String sessionId,
                                                String query,
                                                TaskRuntimeState taskState,
                                                RelationalRuntimeState relationalState,
                                                int limit);

    /**
     * 判断当前输入和任务阶段是否需要触发计划编排逻辑。
     */
    boolean shouldTriggerPlanOrchestration(String query, TaskRuntimeState taskState);
}
