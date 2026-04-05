package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class RoundPipelineRequest {
    String sessionId;
    String userInput;
    OrchestrationDecision decision;
    StructuredContextPackage contextPackage;
    InputReconstructionResult reconstructionResult;
    NodeWorksetResult nodeWorksetResult;
    ToolSemanticResult toolSemanticResult;
    List<String> workingMemorySnippets;
    List<String> runtimeMemorySnippets;
    List<String> retrievedMemorySnippets;
    List<String> knowledgeSnippets;
    List<String> preferenceSnippets;
    List<String> longTermMemorySnippets;
    List<Resource> executionCandidates;
    List<String> mcpResourceHints;
    ContextNodeTemplatePolicy nodeTemplatePolicy;
    String toolContext;
    String stage;
    String repairSeed;
    boolean runMainModel;
    String assistantReplyOverride;
    String preAssemblyTriggerSource;
    String postSummaryTriggerSource;
    boolean writeRoundState;
    String latestSnapshotId;
    String latestToolRawRef;
    List<String> latestToolHistoryRefs;
    Map<String, Object> rawToolResultChannel;
    Map<String, Object> retrievalPlanOverrides;
}

