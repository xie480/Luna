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
import org.yilena.luna.prompt.governance.model.PromptResolveResult;

import java.util.List;
import java.util.Map;

/**
 * 上下文组装器接口，负责将任务状态、检索结果、工具语义和提示词治理结果
 * 整合为最终可供模型使用的上下文。
 */
public interface ContextAssembler {
    /**
     * 组装当前轮次的最终模型上下文。
     */
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

    /**
     * 组装上下文并同步写入可审计快照。
     */
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
                                         Map<String, List<String>> activeRefs,
                                         Map<String, Object> structuredRecoveryPayload);

    /**
     * 兼容带预解析提示词结果的上下文组装入口。
     */
    default AssembledContext assembleAndSnapshot(StructuredContextPackage contextPackage,
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
                                                 Map<String, List<String>> activeRefs,
                                                 Map<String, Object> structuredRecoveryPayload,
                                                 PromptResolveResult promptResolveResult) {
        return assembleAndSnapshot(
                contextPackage,
                reconstructionResult,
                rerankResult,
                toolSemanticResult,
                userInput,
                knowledgeEvidenceBlocks,
                workingMemorySnippets,
                runtimeMemorySnippets,
                retrievedMemorySnippets,
                knowledgeSnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                executionCandidates,
                mcpResourceHints,
                toolContext,
                nodeTemplatePolicy,
                roundSummaryInput,
                sessionId,
                planId,
                nodeId,
                rawToolResultChannel,
                activeRefs,
                structuredRecoveryPayload
        );
    }
}
