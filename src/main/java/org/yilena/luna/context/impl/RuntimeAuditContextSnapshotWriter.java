package org.yilena.luna.context.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.context.ContextSnapshotWriter;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.memory.RuntimeAuditService;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
/**
 * 运行态上下文快照写入器，负责把最终组装结果落入审计存储，
 * 便于恢复、回放和问题追踪。
 */
public class RuntimeAuditContextSnapshotWriter implements ContextSnapshotWriter {

    private final RuntimeAuditService runtimeAuditService;

    @Override
    /**
     * 兼容旧调用方式写入最终上下文快照。
     */
    public String persistFinalSnapshot(String sessionId,
                                       Long planId,
                                       Long nodeId,
                                       AssembledContext assembledContext,
                                       Map<String, Object> rawToolResultChannel,
                                       Map<String, List<String>> activeRefs) {
        return persistFinalSnapshot(sessionId, planId, nodeId, assembledContext, rawToolResultChannel, activeRefs, Map.of());
    }

    @Override
    /**
     * 将最终提示词、分区统计和活动引用一并持久化为可审计快照。
     */
    public String persistFinalSnapshot(String sessionId,
                                       Long planId,
                                       Long nodeId,
                                       AssembledContext assembledContext,
                                       Map<String, Object> rawToolResultChannel,
                                       Map<String, List<String>> activeRefs,
                                       Map<String, Object> structuredRecoveryPayload) {
        if (assembledContext == null || sessionId == null || sessionId.isBlank()) {
            return "";
        }
        /**
         * 将组装结果与运行态附加信息统一交给审计服务落库，
         * 确保后续恢复流程能够拿到完整上下文输入。
         */
        return runtimeAuditService.persistFinalContextSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                assembledContext.getPrompt() == null ? "" : assembledContext.getPrompt(),
                assembledContext.getSectionTokenCounts() == null ? Map.of() : assembledContext.getSectionTokenCounts(),
                assembledContext.getSectionTokenRatios() == null ? Map.of() : assembledContext.getSectionTokenRatios(),
                rawToolResultChannel == null ? Map.of() : rawToolResultChannel,
                activeRefs == null ? Map.of() : activeRefs,
                structuredRecoveryPayload == null ? Map.of() : structuredRecoveryPayload
        );
    }
}
