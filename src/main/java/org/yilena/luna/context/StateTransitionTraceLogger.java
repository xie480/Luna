package org.yilena.luna.context;

/**
 * 状态迁移追踪日志接口，负责记录任务状态在事件驱动下的变化过程。
 */
public interface StateTransitionTraceLogger {
    /**
     * 记录一次状态迁移和其关联的链路信息。
     */
    void log(String traceId,
             String sessionId,
             Long planId,
             Long nodeId,
             String fromTaskState,
             String toTaskState,
             String event,
             String action,
             String snapshotId,
             String recoveryEvent);
}
