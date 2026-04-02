package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.mapper.RuntimeAuditMapper;
import org.yilena.luna.state.store.ContextSnapshotStore;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContextSnapshotStoreImpl implements ContextSnapshotStore {

    private final RuntimeAuditMapper runtimeAuditMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void saveFinalSnapshot(String sessionId,
                                  Long planId,
                                  Long nodeId,
                                  AssembledContext assembledContext,
                                  String prompt,
                                  Map<String, Integer> sectionTokenCounts,
                                  Map<String, Double> sectionTokenRatios) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshotType", "FINAL_MODEL_CONTEXT");
            payload.put("prompt", prompt == null ? "" : prompt);
            payload.put("sections", assembledContext == null ? Map.of() : assembledContext.getSections());
            payload.put("sectionTokenCounts", sectionTokenCounts == null ? Map.of() : sectionTokenCounts);
            payload.put("sectionTokenRatios", sectionTokenRatios == null ? Map.of() : sectionTokenRatios);
            runtimeAuditMapper.insertContextSnapshot(
                    sessionId,
                    planId,
                    nodeId,
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}

