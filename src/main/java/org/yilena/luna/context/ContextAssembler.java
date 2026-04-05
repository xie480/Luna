package org.yilena.luna.context;

import org.yilena.luna.context.model.AssembledContext;
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

public interface ContextAssembler {
    AssembledContext assemble(StructuredContextPackage contextPackage,
                              InputReconstructionResult reconstructionResult,
                              ContextRerankResult rerankResult,
                              ToolSemanticResult toolSemanticResult,
                              String userInput,
                              List<EvidenceBlock> knowledgeEvidenceBlocks,
                              List<String> workingMemorySnippets,
                              List<String> runtimeMemorySnippets,
                              List<String> retrievedMemorySnippets,
                              List<String> knowledgeSnippets,
                              List<String> preferenceSnippets,
                              List<String> longTermMemorySnippets,
                              List<Resource> executionCandidates,
                              List<String> mcpResourceHints,
                              String toolContext,
                              ContextNodeTemplatePolicy nodeTemplatePolicy,
                              SummaryResult roundSummaryInput,
                              String sessionId,
                              Long planId,
                              Long nodeId);

    AssembledContext assembleAndSnapshot(StructuredContextPackage contextPackage,
                                         InputReconstructionResult reconstructionResult,
                                         ContextRerankResult rerankResult,
                                         ToolSemanticResult toolSemanticResult,
                                         String userInput,
                                         List<EvidenceBlock> knowledgeEvidenceBlocks,
                                         List<String> workingMemorySnippets,
                                         List<String> runtimeMemorySnippets,
                                         List<String> retrievedMemorySnippets,
                                         List<String> knowledgeSnippets,
                                         List<String> preferenceSnippets,
                                         List<String> longTermMemorySnippets,
                                         List<Resource> executionCandidates,
                                         List<String> mcpResourceHints,
                                         String toolContext,
                                         ContextNodeTemplatePolicy nodeTemplatePolicy,
                                         SummaryResult roundSummaryInput,
                                         String sessionId,
                                         Long planId,
                                         Long nodeId,
                                         Map<String, Object> rawToolResultChannel,
                                         Map<String, List<String>> activeRefs);
}
