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
public class RuntimeAuditContextSnapshotWriter implements ContextSnapshotWriter {

    private final RuntimeAuditService runtimeAuditService;

    @Override
    public String persistFinalSnapshot(String sessionId,
                                       Long planId,
                                       Long nodeId,
                                       AssembledContext assembledContext,
                                       Map<String, Object> rawToolResultChannel,
                                       Map<String, List<String>> activeRefs) {
        if (assembledContext == null || sessionId == null || sessionId.isBlank()) {
            return "";
        }
        return runtimeAuditService.persistFinalContextSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                assembledContext.getPrompt() == null ? "" : assembledContext.getPrompt(),
                assembledContext.getSectionTokenCounts() == null ? Map.of() : assembledContext.getSectionTokenCounts(),
                assembledContext.getSectionTokenRatios() == null ? Map.of() : assembledContext.getSectionTokenRatios(),
                rawToolResultChannel == null ? Map.of() : rawToolResultChannel,
                activeRefs == null ? Map.of() : activeRefs
        );
    }
}
