package org.yilena.luna.executor;

import org.yilena.luna.entity.Resource;

/**
 * 旧版技能执行器兼容类，负责将历史调用统一转发到新的工作流执行器实现。
 */
@Deprecated(forRemoval = true)
public class SkillExecutor {

    /**
     * 新版工作流执行器。
     */
    private final WorkflowExecutor workflowExecutor;

    public SkillExecutor(WorkflowExecutor workflowExecutor) {
        this.workflowExecutor = workflowExecutor;
    }

    /**
     * 兼容旧版同步执行入口，内部转发给工作流执行器。
     */
    public String execute(Resource skill, String argsJson) {
        return workflowExecutor.execute(skill, argsJson);
    }

    /**
     * 兼容旧版循环执行入口，内部转发给工作流执行器。
     */
    public String executeLoop(Resource skill, String argsJson) {
        return workflowExecutor.executeLoop(skill, argsJson);
    }
}
