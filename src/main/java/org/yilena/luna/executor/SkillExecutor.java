package org.yilena.luna.executor;

import org.yilena.luna.entity.Resource;

/**
 * @deprecated Use {@link WorkflowExecutor}. Kept only for compatibility.
 */
@Deprecated(forRemoval = true)
public class SkillExecutor {

    private final WorkflowExecutor workflowExecutor;

    public SkillExecutor(WorkflowExecutor workflowExecutor) {
        this.workflowExecutor = workflowExecutor;
    }

    public String execute(Resource skill, String argsJson) {
        return workflowExecutor.execute(skill, argsJson);
    }

    public String executeLoop(Resource skill, String argsJson) {
        return workflowExecutor.executeLoop(skill, argsJson);
    }
}
