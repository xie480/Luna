package org.yilena.luna.context;

public interface StateTransitionTraceLogger {
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

