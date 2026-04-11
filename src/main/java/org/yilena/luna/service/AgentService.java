package org.yilena.luna.service;

import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.service.model.ToolDecisionCommand;

import java.util.List;

/**
 * Agent 工具决策服务接口，负责根据治理后的输入、运行状态和候选资源决定是否发起工具调用，
 * 在会话执行链路中承担能力选择与兼容旧调用入口的职责。
 */
public interface AgentService {

    String processToolCallingWithGovernance(ToolDecisionCommand command);

    @Deprecated
    default String processToolCalling(String sessionId,
                                      String input,
                                      TaskRuntimeState taskState,
                                      RelationalRuntimeState relationalState) {
        return processToolCalling(sessionId, input, taskState, relationalState, List.of());
    }

    @Deprecated
    default String processToolCalling(String sessionId,
                                      String input,
                                      TaskRuntimeState taskState,
                                      RelationalRuntimeState relationalState,
                                      List<Resource> executionCandidates) {
        return processToolCallingWithGovernance(
                ToolDecisionCommand.builder()
                        .sessionId(sessionId)
                        .rawUserInput(input)
                        .toolDecisionInput(input)
                        .taskState(taskState)
                        .relationalState(relationalState)
                        .executionCandidates(executionCandidates == null ? List.of() : executionCandidates)
                        .governedInputSignature("")
                        .assembledDecisionContext("")
                        .build()
        );
    }

    @Deprecated
    default String processToolCalling(String sessionId,
                                      String input,
                                      TaskRuntimeState taskState,
                                      RelationalRuntimeState relationalState,
                                      List<Resource> executionCandidates,
                                      String assembledDecisionContext) {
        return processToolCallingWithGovernance(
                ToolDecisionCommand.builder()
                        .sessionId(sessionId)
                        .rawUserInput(input)
                        .toolDecisionInput(input)
                        .taskState(taskState)
                        .relationalState(relationalState)
                        .executionCandidates(executionCandidates == null ? List.of() : executionCandidates)
                        .governedInputSignature("")
                        .assembledDecisionContext(assembledDecisionContext == null ? "" : assembledDecisionContext)
                        .build()
        );
    }

    @Deprecated
    default String processToolCalling(String input) {
        return processToolCallingWithGovernance(
                ToolDecisionCommand.builder()
                        .sessionId("agent-default")
                        .rawUserInput(input)
                        .toolDecisionInput(input)
                        .taskState(null)
                        .relationalState(null)
                        .executionCandidates(List.of())
                        .governedInputSignature("")
                        .assembledDecisionContext("")
                        .build()
        );
    }
}
