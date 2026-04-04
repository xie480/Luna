package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class MainModelExecutionRequest {
    String sessionId;
    String userInput;
    StructuredContextPackage contextPackage;
    InputReconstructionResult reconstructionResult;
    ContextRerankResult rerankResult;
    ToolSemanticResult toolSemanticResult;
    List<EvidenceBlock> knowledgeEvidenceBlocks;
    List<String> workingMemorySnippets;
    List<String> runtimeMemorySnippets;
    List<String> retrievedMemorySnippets;
    List<String> knowledgeSnippets;
    List<String> preferenceSnippets;
    List<String> longTermMemorySnippets;
    List<Resource> executionCandidates;
    List<String> mcpResourceHints;
    String toolContext;
    ContextNodeTemplatePolicy nodeTemplatePolicy;
    SummaryResult roundSummaryInput;
    Long planId;
    Long nodeId;
    String stage;
    String repairSeed;
    Map<String, Object> rawToolResultChannel;
}

