package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.memory.RuntimeAuditService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 状态迁移追踪日志实现，负责记录任务状态在事件驱动下的转换轨迹，
 * 便于还原编排链路的推进过程。
 */
public class RuntimeStateTransitionTraceLogger implements StateTransitionTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 记录一次任务状态迁移及其关联快照信息。
     */
    public void log(String traceId,
                    String sessionId,
                    Long planId,
                    Long nodeId,
                    String fromTaskState,
                    String toTaskState,
                    String event,
                    String action,
                    String snapshotId,
                    String recoveryEvent) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            /**
             * 将迁移前后状态、触发事件和恢复上下文固化为统一审计载荷，
             * 方便跨阶段排查异常跳转。
             */
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceId == null ? "" : traceId);
            payload.put("sessionId", sessionId);
            payload.put("planId", planId);
            payload.put("nodeId", nodeId);
            payload.put("fromTaskState", fromTaskState == null ? "" : fromTaskState);
            payload.put("toTaskState", toTaskState == null ? "" : toTaskState);
            payload.put("event", event == null ? "" : event);
            payload.put("action", action == null ? "" : action);
            payload.put("snapshotId", snapshotId == null ? "" : snapshotId);
            payload.put("recoveryEvent", recoveryEvent == null ? "" : recoveryEvent);
            payload.put("timestamp", Instant.now().toString());
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "STATE_TRANSITION_TRACE",
                    "state transition index trace",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}
