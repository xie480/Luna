package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.RerankTraceLogger;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.memory.RuntimeAuditService;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuntimeRerankTraceLogger implements RerankTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult) {
        log(sessionId, planId, nodeId, rerankResult, Map.of());
    }

    @Override
    public void log(String sessionId, Long planId, Long nodeId, ContextRerankResult rerankResult, Map<String, Object> traceMeta) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("traceId", "")));
            payload.put("traceLayer", "GLOBAL_RERANK");
            payload.put("nodeId", nodeId);
            payload.put("snapshotId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("snapshotId", "")));
            payload.put("recoveryEvent", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("recoveryEvent", "")));
            payload.put("selectedKnowledgeEvidenceBlocks", rerankResult == null ? java.util.List.of() : rerankResult.getSelectedKnowledgeEvidenceBlocks());
            payload.put("selectedKnowledgeBlocks", rerankResult == null ? java.util.List.of() : rerankResult.getSelectedKnowledgeBlocks());
            payload.put("selectedToolCandidates", rerankResult == null ? java.util.List.of() : rerankResult.getSelectedToolCandidates());
            payload.put("selectedPromptResources", rerankResult == null ? java.util.List.of() : rerankResult.getSelectedPromptResources());
            payload.put("selectedMemoryHints", rerankResult == null ? java.util.List.of() : rerankResult.getSelectedMemoryHints());
            payload.put("duplicateClusters", rerankResult == null ? java.util.List.of() : rerankResult.getDuplicateClusters());
            payload.put("rejectedCandidates", rerankResult == null ? java.util.List.of() : rerankResult.getRejectedCandidates());
            payload.put("rationaleByNode", rerankResult == null ? java.util.Map.of() : rerankResult.getRationaleByNode());
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "RERANK_TRACE_GLOBAL_SELECTION",
                    "global semantic rerank selected candidates",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}
