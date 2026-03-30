package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.Map;

public interface ResponseSynthesizerService {
    Map<String, Object> buildSynthesisPolicy(TaskRuntimeState taskState,
                                             RelationalRuntimeState relationalState,
                                             Map<String, Object> taskContext,
                                             Map<String, Object> relationalContext,
                                             Map<String, Object> socialDraft);
}
