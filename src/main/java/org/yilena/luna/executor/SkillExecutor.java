package org.yilena.luna.executor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.Resource;

/**
 * @deprecated Use {@link WorkflowExecutor}. Kept only for compatibility.
 */
@Deprecated
@Component
@RequiredArgsConstructor
public class SkillExecutor {

    private final WorkflowExecutor workflowExecutor;

    public String execute(Resource skill, String argsJson) {
        return workflowExecutor.execute(skill, argsJson);
    }

    public String executeLoop(Resource skill, String argsJson) {
        return workflowExecutor.executeLoop(skill, argsJson);
    }
}

