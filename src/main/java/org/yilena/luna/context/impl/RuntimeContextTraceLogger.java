package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.memory.RuntimeAuditService;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 上下文组装追踪日志实现，负责记录最终分区内容和候选池选择结果，
 * 方便排查上下文装配问题。
 */
public class RuntimeContextTraceLogger implements ContextTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 记录默认上下文追踪日志。
     */
    public void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext) {
        log(sessionId, planId, nodeId, assembledContext, Map.of());
    }

    @Override
    /**
     * 将上下文分区、规范化分区和候选池写入运行态审计日志。
     */
    public void log(String sessionId, Long planId, Long nodeId, AssembledContext assembledContext, Map<String, Object> traceMeta) {
        try {
            /**
             * 组装统一追踪载荷，保留 traceId、恢复事件和快照编号，
             * 便于串联同一轮上下文装配链路。
             */
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("traceId", "")));
            payload.put("traceLayer", "CONTEXT_ASSEMBLY");
            payload.put("nodeId", nodeId);
            payload.put("sections", assembledContext == null ? java.util.Map.of() : assembledContext.getSections());
            payload.put("canonicalSections", assembledContext == null ? java.util.Map.of() : assembledContext.getCanonicalSections());
            payload.put("candidatePool", assembledContext == null ? java.util.Map.of() : assembledContext.getCandidatePool());
            payload.put("snapshotId", assembledContext == null ? "" : assembledContext.getSnapshotId());
            payload.put("recoveryEvent", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("recoveryEvent", "")));
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "CONTEXT_TRACE",
                    "context sections assembled",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}
