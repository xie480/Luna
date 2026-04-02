package org.yilena.luna.service;

import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;

public interface AgentService {

    String processToolCalling(String sessionId, String input);

    default String processToolCalling(String sessionId,
                                      String input,
                                      TaskRuntimeState taskState,
                                      RelationalRuntimeState relationalState) {
        return processToolCalling(sessionId, input);
    }

    default String processToolCalling(String sessionId,
                                      String input,
                                      TaskRuntimeState taskState,
                                      RelationalRuntimeState relationalState,
                                      List<Resource> executionCandidates) {
        return processToolCalling(sessionId, input, taskState, relationalState);
    }

    default String processToolCalling(String input) {
        return processToolCalling("agent-default", input);
    }
}
