package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;

import java.util.List;
import java.util.Map;

/**
 * 上下文快照写入器接口，负责把最终组装出的上下文持久化为可恢复、可审计的快照。
 */
public interface ContextSnapshotWriter {
    /**
     * 持久化最终上下文快照。
     */
    String persistFinalSnapshot(String sessionId,
                                Long planId,
                                Long nodeId,
                                AssembledContext assembledContext,
                                Map<String, Object> rawToolResultChannel,
                                Map<String, List<String>> activeRefs);

    /**
     * 持久化带结构化恢复载荷的最终上下文快照。
     */
    default String persistFinalSnapshot(String sessionId,
                                        Long planId,
                                        Long nodeId,
                                        AssembledContext assembledContext,
                                        Map<String, Object> rawToolResultChannel,
                                        Map<String, List<String>> activeRefs,
                                        Map<String, Object> structuredRecoveryPayload) {
        return persistFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                rawToolResultChannel,
                activeRefs
        );
    }
}
