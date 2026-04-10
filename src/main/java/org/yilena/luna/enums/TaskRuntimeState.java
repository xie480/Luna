package org.yilena.luna.enums;

/**
 * 任务运行态枚举，用于描述任务编排执行过程中的实时阶段。
 */
public enum TaskRuntimeState {
    /**
     * 空闲状态。
     */
    IDLE,
    /**
     * 正在理解输入。
     */
    UNDERSTANDING,
    /**
     * 正在构建上下文。
     */
    CONTEXT_BUILDING,
    /**
     * 正在规划任务。
     */
    PLANNING,
    /**
     * 等待用户确认计划。
     */
    WAITING_PLAN_CONFIRMATION,
    /**
     * 正在执行任务。
     */
    EXECUTING,
    /**
     * 等待工具返回。
     */
    WAITING_TOOL,
    /**
     * 等待用户输入。
     */
    WAITING_USER,
    /**
     * 等待审批结果。
     */
    WAITING_APPROVAL,
    /**
     * 正在反思总结。
     */
    REFLECTING,
    /**
     * 正在重新规划。
     */
    REPLANNING,
    /**
     * 正在生成报告。
     */
    REPORTING,
    /**
     * 任务已完成。
     */
    COMPLETED,
    /**
     * 任务执行失败。
     */
    FAILED,
    /**
     * 任务已取消。
     */
    CANCELLED
}
