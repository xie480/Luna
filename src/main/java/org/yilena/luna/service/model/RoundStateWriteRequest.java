package org.yilena.luna.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundStateWriteRequest {

    private String sessionId;
    private OrchestrationDecision decision;
    private StructuredContextPackage contextPackage;
    private InputReconstructionResult reconstruction;
    private ContextRerankResult rerankResult;
    private ToolSemanticResult toolSemanticResult;
    private SummaryResult summaryResult;
    private String latestSnapshotId;
    private String latestToolRawRef;
    private List<String> latestToolHistoryRefs;
    private String ragQuery;
    private String memoryQuery;
    private String mcpQuery;
    private Map<String, Object> retrievalPlanOverrides;
}
