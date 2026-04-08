package org.yilena.luna.context.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.ContextSnapshotWriter;
import org.yilena.luna.context.SemanticPreservingPruner;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.PromptSnapshotBridgeService;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;
import org.yilena.luna.prompt.governance.support.PromptSectionAssemblerSupport;
import org.yilena.luna.state.model.TaskState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultContextAssembler implements ContextAssembler {

    private final SemanticPreservingPruner semanticPreservingPruner;
    private final TaskMemoryRetriever taskMemoryRetriever;
    private final RelationalMemoryRetriever relationalMemoryRetriever;
    private final SummaryAgent summaryAgent;
    private final ToolSemanticAgent toolSemanticAgent;
    private final ContextSnapshotWriter contextSnapshotWriter;
    @Autowired(required = false)
    private PromptResolverService promptResolverService;
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;
    @Autowired(required = false)
    private PromptSnapshotBridgeService promptSnapshotBridgeService;

    public DefaultContextAssembler(SemanticPreservingPruner semanticPreservingPruner,
                                   TaskMemoryRetriever taskMemoryRetriever,
                                   RelationalMemoryRetriever relationalMemoryRetriever,
                                   SummaryAgent summaryAgent,
                                   ToolSemanticAgent toolSemanticAgent,
                                   ContextSnapshotWriter contextSnapshotWriter) {
        this.semanticPreservingPruner = semanticPreservingPruner;
        this.taskMemoryRetriever = taskMemoryRetriever;
        this.relationalMemoryRetriever = relationalMemoryRetriever;
        this.summaryAgent = summaryAgent;
        this.toolSemanticAgent = toolSemanticAgent;
        this.contextSnapshotWriter = contextSnapshotWriter;
    }

    @Override
    public AssembledContext assemble(StructuredContextPackage contextPackage,
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
                                       Long nodeId) {
        return assembleInternal(
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
                null
        );
    }

    private AssembledContext assembleInternal(StructuredContextPackage contextPackage,
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
                                              PromptResolveResult preResolvedPromptAssembly) {
        ContextNodeTemplatePolicy policy = nodeTemplatePolicy == null ? ContextNodeTemplatePolicy.defaultPolicy() : nodeTemplatePolicy;
        ToolSemanticResult effectiveToolSemanticResult = toolSemanticResult == null
                ? resolveOnDemandToolSemantic(executionCandidates, toolContext, contextPackage, reconstructionResult)
                : toolSemanticResult;
        SummaryResult effectiveRoundSummaryInput = roundSummaryInput == null
                ? resolveOnDemandRoundSummary(
                userInput,
                contextPackage,
                knowledgeEvidenceBlocks,
                mcpResourceHints,
                effectiveToolSemanticResult
        )
                : roundSummaryInput;
        OnDemandMemoryPayload onDemandMemory = resolveOnDemandMemory(
                sessionId,
                contextPackage,
                reconstructionResult,
                policy,
                workingMemorySnippets,
                retrievedMemorySnippets,
                knowledgeSnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                knowledgeEvidenceBlocks
        );
        List<String> effectiveWorkingMemorySnippets = mergeDistinct(workingMemorySnippets, onDemandMemory.workingMemorySnippets());
        List<String> effectiveRetrievedMemorySnippets = mergeDistinct(retrievedMemorySnippets, onDemandMemory.retrievedMemorySnippets());
        List<String> effectiveKnowledgeSnippets = mergeDistinct(knowledgeSnippets, onDemandMemory.knowledgeSnippets());
        List<String> effectivePreferenceSnippets = mergeDistinct(preferenceSnippets, onDemandMemory.preferenceSnippets());
        List<String> effectiveLongTermMemorySnippets = mergeDistinct(longTermMemorySnippets, onDemandMemory.longTermMemorySnippets());
        Map<String, List<String>> candidatePool = buildCandidatePool(
                userInput,
                rerankResult,
                knowledgeEvidenceBlocks,
                effectiveWorkingMemorySnippets,
                runtimeMemorySnippets,
                effectiveRetrievedMemorySnippets,
                effectiveKnowledgeSnippets,
                effectivePreferenceSnippets,
                effectiveLongTermMemorySnippets,
                executionCandidates,
                mcpResourceHints,
                effectiveToolSemanticResult,
                toolContext,
                effectiveRoundSummaryInput,
                policy
        );
        PromptResolveResult promptResolveResult = preResolvedPromptAssembly == null
                ? resolvePromptAssembly(userInput, contextPackage, policy)
                : preResolvedPromptAssembly;
        PromptValueSelection systemPromptSelection = resolveSystemPromptSelection(promptResolveResult);
        PromptValueSelection runtimePromptSelection = resolveRuntimePromptTemplateSelection(promptResolveResult);
        String systemPrompt = systemPromptSelection.value();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Instructions", lines(systemPrompt));
        sections.put("Current Task State", lines(buildCurrentTaskState(contextPackage, policy)));
        sections.put("Reconstructed User Intent", lines(buildReconstructedIntent(userInput, reconstructionResult)));
        sections.put("Relevant Knowledge Evidence", candidatePool.getOrDefault("knowledge", List.of()));
        sections.put("MCP Resource / Prompt Hints", mergeDistinct(
                mergeDistinct(
                        candidatePool.getOrDefault("mcp_prompt", List.of()),
                        candidatePool.getOrDefault("mcp_resource", List.of())
                ),
                candidatePool.getOrDefault("mcp_workflow", List.of())
        ));
        sections.put("Tool Evidence", mergeDistinct(
                candidatePool.getOrDefault("tool", List.of()),
                candidatePool.getOrDefault("mcp_tool", List.of())
        ));
        sections.put("Recent Interaction Context", mergeDistinct(
                lines(buildRecentInteraction(contextPackage, runtimeMemorySnippets, policy)),
                candidatePool.getOrDefault("raw_input", List.of())
        ));
        sections.put("Memory Hints", mergeDistinct(
                candidatePool.getOrDefault("memory", List.of()),
                candidatePool.getOrDefault("summary", List.of())
        ));
        sections.put("Output Constraints", buildOutputConstraints(policy, contextPackage, reconstructionResult, effectiveToolSemanticResult, effectiveRoundSummaryInput));
        PromptSectionAssemblerSupport.applyResolvedPromptSlots(
                sections,
                promptResolveResult == null ? Map.of() : promptResolveResult.getSlotMapping()
        );

        SemanticPreservingPruner.PruneResult pruneResult = semanticPreservingPruner.prune(
                sections,
                sectionBudget(contextPackage == null ? Map.of() : contextPackage.getTokenBudgetPlan(), policy)
        );
        if (pruneResult.getConsistencyViolations() != null && !pruneResult.getConsistencyViolations().isEmpty()) {
            List<String> outputConstraints = new ArrayList<>(pruneResult.getSections().getOrDefault("Output Constraints", List.of()));
            outputConstraints.add("semantic_consistency_guard=" + safe(pruneResult.getConsistencyViolations()));
            Map<String, List<String>> patched = new LinkedHashMap<>(pruneResult.getSections());
            patched.put("Output Constraints", outputConstraints.stream().distinct().toList());
            pruneResult = semanticPreservingPruner.prune(
                    patched,
                    sectionBudget(contextPackage == null ? Map.of() : contextPackage.getTokenBudgetPlan(), policy)
            );
        }
        String prompt = toPrompt(
                pruneResult.getSections(),
                buildRuntimePromptInput(userInput, reconstructionResult),
                runtimePromptSelection.value()
        );
        Map<String, List<String>> canonicalSections = toCanonicalSections(pruneResult.getSections());
        Map<String, Object> promptAssemblyMeta = buildPromptAssemblyMeta(
                promptResolveResult,
                List.of(systemPromptSelection.ref(), runtimePromptSelection.ref()),
                resolvePolicyId(contextPackage)
        );
        AssembledContext preSnapshotContext = AssembledContext.builder()
                .prompt(prompt)
                .sections(pruneResult.getSections())
                .canonicalSections(canonicalSections)
                .candidatePool(candidatePool)
                .sectionTokenCounts(pruneResult.getSectionTokenCounts())
                .sectionTokenRatios(pruneResult.getSectionTokenRatios())
                .promptAssemblyMeta(promptAssemblyMeta)
                .snapshotId("")
                .build();
        return AssembledContext.builder()
                .prompt(preSnapshotContext.getPrompt())
                .sections(preSnapshotContext.getSections())
                .canonicalSections(preSnapshotContext.getCanonicalSections())
                .candidatePool(preSnapshotContext.getCandidatePool())
                .sectionTokenCounts(preSnapshotContext.getSectionTokenCounts())
                .sectionTokenRatios(preSnapshotContext.getSectionTokenRatios())
                .promptAssemblyMeta(preSnapshotContext.getPromptAssemblyMeta())
                .snapshotId("")
                .build();
    }

    @Override
    public AssembledContext assembleAndSnapshot(StructuredContextPackage contextPackage,
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
                                                Map<String, Object> structuredRecoveryPayload) {
        AssembledContext assembled = assembleInternal(
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
                null
        );
        return snapshotAssembledContext(
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
                structuredRecoveryPayload,
                assembled
        );
    }

    @Override
    public AssembledContext assembleAndSnapshot(StructuredContextPackage contextPackage,
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
        AssembledContext assembled = assembleInternal(
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
                promptResolveResult
        );
        return snapshotAssembledContext(
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
                structuredRecoveryPayload,
                assembled
        );
    }

    private AssembledContext snapshotAssembledContext(StructuredContextPackage contextPackage,
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
                                                      AssembledContext assembled) {
        String snapshotId = contextSnapshotWriter.persistFinalSnapshot(
                sessionId,
                planId,
                nodeId,
                assembled,
                rawToolResultChannel,
                activeRefs,
                structuredRecoveryPayload
        );
        return AssembledContext.builder()
                .prompt(assembled == null ? "" : assembled.getPrompt())
                .sections(assembled == null ? Map.of() : assembled.getSections())
                .canonicalSections(assembled == null ? Map.of() : assembled.getCanonicalSections())
                .candidatePool(assembled == null ? Map.of() : assembled.getCandidatePool())
                .sectionTokenCounts(assembled == null ? Map.of() : assembled.getSectionTokenCounts())
                .sectionTokenRatios(assembled == null ? Map.of() : assembled.getSectionTokenRatios())
                .promptAssemblyMeta(assembled == null ? Map.of() : assembled.getPromptAssemblyMeta())
                .snapshotId(snapshotId == null ? "" : snapshotId)
                .build();
    }

    private Map<String, List<String>> buildCandidatePool(String userInput,
                                                         ContextRerankResult rerankResult,
                                                         List<EvidenceBlock> knowledgeEvidenceBlocks,
                                                         List<String> workingMemorySnippets,
                                                         List<String> runtimeMemorySnippets,
                                                         List<String> retrievedMemorySnippets,
                                                         List<String> knowledgeSnippets,
                                                         List<String> preferenceSnippets,
                                                         List<String> longTermMemorySnippets,
                                                         List<Resource> executionCandidates,
                                                         List<String> mcpResourceHints,
                                                         ToolSemanticResult toolSemanticResult,
                                                         String toolContext,
                                                         SummaryResult roundSummaryInput,
                                                         ContextNodeTemplatePolicy policy) {
        Map<String, List<String>> pool = new LinkedHashMap<>();
        pool.put("knowledge", selectKnowledgeCandidates(rerankResult, knowledgeEvidenceBlocks, knowledgeSnippets));
        Map<String, List<String>> mcpBuckets = selectMcpCandidates(rerankResult, executionCandidates, mcpResourceHints);
        pool.put("mcp_prompt", mcpBuckets.getOrDefault("mcp_prompt", List.of()));
        pool.put("mcp_resource", mcpBuckets.getOrDefault("mcp_resource", List.of()));
        pool.put("mcp_workflow", mcpBuckets.getOrDefault("mcp_workflow", List.of()));
        pool.put("mcp_tool", mcpBuckets.getOrDefault("mcp_tool", List.of()));
        pool.put("tool", lines(buildToolEvidence(toolContext, toolSemanticResult)));
        pool.put("raw_input", buildRawInputFact(userInput));
        pool.put("memory", buildMemoryHints(
                workingMemorySnippets,
                runtimeMemorySnippets,
                retrievedMemorySnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                rerankResult,
                policy
        ));
        pool.put("summary", buildSummaryInput(roundSummaryInput));
        return pool;
    }

    private List<String> selectKnowledgeCandidates(ContextRerankResult rerankResult,
                                                   List<EvidenceBlock> knowledgeEvidenceBlocks,
                                                   List<String> knowledgeSnippets) {
        List<String> out = new ArrayList<>();
        if (rerankResult != null && rerankResult.getSelectedKnowledgeEvidenceBlocks() != null
                && !rerankResult.getSelectedKnowledgeEvidenceBlocks().isEmpty()) {
            for (EvidenceBlock block : rerankResult.getSelectedKnowledgeEvidenceBlocks()) {
                if (block == null) {
                    continue;
                }
                out.add("id=" + safe(block.getBlockId())
                        + "; source=" + safe(block.getSourceType())
                        + "; score=" + safe(block.getScore())
                        + "; title=" + safe(block.getTitle())
                        + "; content=" + safe(block.getContent())
                        + "; metadata=" + safe(block.getMetadata()));
            }
        } else if (rerankResult != null && rerankResult.getSelectedKnowledgeBlocks() != null && !rerankResult.getSelectedKnowledgeBlocks().isEmpty()) {
            out.addAll(rerankResult.getSelectedKnowledgeBlocks());
        }
        if (out.isEmpty() && knowledgeEvidenceBlocks != null) {
            for (EvidenceBlock block : knowledgeEvidenceBlocks) {
                if (block == null) {
                    continue;
                }
                out.add("id=" + safe(block.getBlockId())
                        + "; source=" + safe(block.getSourceType())
                        + "; score=" + safe(block.getScore())
                        + "; title=" + safe(block.getTitle())
                        + "; content=" + safe(block.getContent())
                        + "; metadata=" + safe(block.getMetadata()));
            }
        }
        if (out.isEmpty() && knowledgeSnippets != null) {
            out.addAll(knowledgeSnippets);
        }
        return out.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(12).toList();
    }

    private Map<String, List<String>> selectMcpCandidates(ContextRerankResult rerankResult,
                                                          List<Resource> executionCandidates,
                                                          List<String> mcpResourceHints) {
        List<String> toolHints = new ArrayList<>();
        List<String> promptHints = new ArrayList<>();
        List<String> resourceHints = new ArrayList<>();
        List<String> workflowHints = new ArrayList<>();
        if (rerankResult != null) {
            if (rerankResult.getSelectedToolCandidates() != null) {
                rerankResult.getSelectedToolCandidates().forEach(row -> toolHints.add("tool_candidate=" + safe(row)));
            }
            if (rerankResult.getSelectedPromptCandidates() != null) {
                rerankResult.getSelectedPromptCandidates().forEach(row -> promptHints.add("prompt_candidate=" + safe(row)));
            }
            if (rerankResult.getSelectedResourceCandidates() != null) {
                rerankResult.getSelectedResourceCandidates().forEach(row -> resourceHints.add("resource_candidate=" + safe(row)));
            }
            if (rerankResult.getSelectedWorkflowCandidates() != null) {
                rerankResult.getSelectedWorkflowCandidates().forEach(row -> workflowHints.add("workflow_candidate=" + safe(row)));
            }
            if (rerankResult.getRationaleByNode() != null && !rerankResult.getRationaleByNode().isEmpty()) {
                workflowHints.add("rerank_rationale=" + safe(rerankResult.getRationaleByNode()));
            }
        }
        if (executionCandidates != null) {
            for (Resource candidate : executionCandidates) {
                if (candidate == null) {
                    continue;
                }
                toolHints.add("execution_candidate="
                        + safe(candidate.getName())
                        + "|type=" + (candidate.getType() == null ? "" : candidate.getType().name())
                        + "|server=" + safe(candidate.getServerCode())
                        + "|approval=" + safe(candidate.getRequiresApproval()));
            }
        }
        if (mcpResourceHints != null) {
            for (String hint : mcpResourceHints) {
                String value = safe(hint);
                if (value.isBlank()) {
                    continue;
                }
                if (value.startsWith("prompt_hint:")) {
                    promptHints.add("mcp_hint=" + value);
                } else if (value.startsWith("resource_hint:")) {
                    resourceHints.add("mcp_hint=" + value);
                } else if (value.startsWith("workflow_hint:")) {
                    workflowHints.add("mcp_hint=" + value);
                } else if (value.startsWith("tool_hint:")) {
                    toolHints.add("mcp_hint=" + value);
                } else {
                    promptHints.add("mcp_hint=" + value);
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("mcp_tool", toolHints.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(10).toList());
        out.put("mcp_prompt", promptHints.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(10).toList());
        out.put("mcp_resource", resourceHints.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(10).toList());
        out.put("mcp_workflow", workflowHints.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(10).toList());
        return out;
    }

    private String buildCurrentTaskState(StructuredContextPackage contextPackage, ContextNodeTemplatePolicy policy) {
        if (contextPackage == null) {
            return "taskState=UNKNOWN; relationalState=UNKNOWN; nodeTemplate=" + safe(policy == null ? null : policy.getNodeType());
        }
        String explicitTask = contextPackage.getTaskStateEntity() == null ? "" : safe(contextPackage.getTaskStateEntity());
        String retrievalState = contextPackage.getRetrievalState() == null ? "" : safe(contextPackage.getRetrievalState());
        String toolState = contextPackage.getToolState() == null ? "" : safe(contextPackage.getToolState());
        String contextState = contextPackage.getContextState() == null ? "" : safe(contextPackage.getContextState());
        String recoveryState = contextPackage.getRecoveryState() == null ? "" : safe(contextPackage.getRecoveryState());
        return "taskState=" + (contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name())
                + "; relationalState=" + (contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name())
                + "; explicitTaskState=" + explicitTask
                + "; retrievalState=" + retrievalState
                + "; toolState=" + toolState
                + "; contextState=" + contextState
                + "; recoveryState=" + recoveryState
                + "; tokenBudget=" + safe(contextPackage.getTokenBudgetPlan())
                + "; nodeTemplate=" + safe(policy == null ? null : policy.getNodeType())
                + "; nodeTemplatePolicy=" + safe(policy);
    }

    private String buildReconstructedIntent(String userInput, InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null) {
            return "normalizedIntent=reconstruction_unavailable"
                    + "; explicitGoal="
                    + "; entities={}"
                    + "; constraints=[]"
                    + "; timeScope=unspecified"
                    + "; missingSlots=[reconstruction_unavailable]"
                    + "; intentConfidence=0.0"
                    + "; rawInputArchived=true"
                    + "; rawInputLength=" + (userInput == null ? 0 : userInput.trim().length())
                    + "; rawInputFactRef=" + rawInputFactRef(userInput);
        }
        return "normalizedIntent=" + safe(reconstructionResult.getNormalizedUserIntent())
                + "; explicitGoal=" + safe(reconstructionResult.getExplicitTaskGoal())
                + "; entities=" + safe(reconstructionResult.getClarifiedEntities())
                + "; constraints=" + safe(reconstructionResult.getBusinessConstraints())
                + "; timeScope=" + safe(reconstructionResult.getTimeScope())
                + "; missingSlots=" + safe(reconstructionResult.getMissingSlots())
                + "; intentConfidence=" + reconstructionResult.getIntentConfidence()
                + "; rawInputFactRef=" + rawInputFactRef(userInput);
    }

    private String buildToolEvidence(String toolContext, ToolSemanticResult toolSemanticResult) {
        String semantic = toolSemanticResult == null ? "" : safe(toolSemanticResult.getSemanticPayload());
        return "rawToolContext=" + safe(toolContext) + "\nsemanticToolContext=" + semantic;
    }

    private ToolSemanticResult resolveOnDemandToolSemantic(List<Resource> executionCandidates,
                                                           String toolContext,
                                                           StructuredContextPackage contextPackage,
                                                           InputReconstructionResult reconstructionResult) {
        if (toolContext == null || toolContext.isBlank()) {
            return null;
        }
        String toolName = resolvePrimaryToolName(executionCandidates);
        String toolDescription = resolvePrimaryToolDescription(executionCandidates);
        return toolSemanticAgent.translate(
                toolName,
                toolDescription,
                toolContext,
                contextPackage == null ? null : contextPackage.getTaskState(),
                reconstructionResult == null ? "" : safe(reconstructionResult.getExplicitTaskGoal())
        );
    }

    private SummaryResult resolveOnDemandRoundSummary(String userInput,
                                                      StructuredContextPackage contextPackage,
                                                      List<EvidenceBlock> activeEvidenceBlocks,
                                                      List<String> activeMcpResourceHints,
                                                      ToolSemanticResult toolSemanticResult) {
        return summaryAgent.summarize(
                userInput,
                "",
                contextPackage,
                activeEvidenceBlocks == null ? List.of() : activeEvidenceBlocks,
                activeMcpResourceHints == null ? List.of() : activeMcpResourceHints,
                toolSemanticResult
        );
    }

    private String resolvePrimaryToolName(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "agent_tool_chain";
        }
        Resource first = executionCandidates.get(0);
        return first == null || first.getName() == null || first.getName().isBlank()
                ? "agent_tool_chain"
                : first.getName();
    }

    private String resolvePrimaryToolDescription(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "";
        }
        Resource first = executionCandidates.get(0);
        if (first == null) {
            return "";
        }
        return "type=" + (first.getType() == null ? "" : first.getType().name())
                + ", server=" + safe(first.getServerCode())
                + ", resourceUri=" + safe(first.getResourceUri());
    }

    private String buildRecentInteraction(StructuredContextPackage contextPackage,
                                          List<String> runtimeMemorySnippets,
                                          ContextNodeTemplatePolicy policy) {
        List<String> lines = new ArrayList<>();
        if (policy != null && policy.isIncludeRuntimeMemory() && runtimeMemorySnippets != null && !runtimeMemorySnippets.isEmpty()) {
            lines.addAll(limit(runtimeMemorySnippets, policy.getMaxRuntimeMemoryItems()));
        }
        if (lines.isEmpty() && contextPackage != null && contextPackage.getRecentMessages() != null) {
            List<Map<String, Object>> messages = contextPackage.getRecentMessages();
            int from = Math.max(0, messages.size() - 8);
            for (Map<String, Object> row : messages.subList(from, messages.size())) {
                lines.add(safe(row.get("role")) + ": " + safe(row.get("content_text")));
            }
        }
        return String.join("\n", lines.stream().filter(line -> line != null && !line.isBlank()).toList());
    }

    private List<String> buildMemoryHints(List<String> workingMemorySnippets,
                                          List<String> runtimeMemorySnippets,
                                          List<String> retrievedMemorySnippets,
                                          List<String> preferenceSnippets,
                                          List<String> longTermMemorySnippets,
                                          ContextRerankResult rerankResult,
                                          ContextNodeTemplatePolicy policy) {
        List<String> out = new ArrayList<>();
        if (policy != null && policy.isIncludeWorkingMemory()) {
            out.addAll(limit(workingMemorySnippets, policy.getMaxWorkingMemoryItems()));
        }
        if (policy != null && policy.isIncludeRuntimeMemory()) {
            out.addAll(limit(runtimeMemorySnippets, policy.getMaxRuntimeMemoryItems()));
        }
        if (policy != null && policy.isIncludeRetrievedMemory()) {
            out.addAll(limit(retrievedMemorySnippets, policy.getMaxRetrievedMemoryItems()));
            if (rerankResult != null && rerankResult.getSelectedMemoryHints() != null) {
                out.addAll(limit(rerankResult.getSelectedMemoryHints(), policy.getMaxRetrievedMemoryItems()));
            }
        }
        if (policy != null && policy.isIncludeLongTermMemory()) {
            out.addAll(limit(preferenceSnippets, Math.max(4, policy.getMaxLongTermMemoryItems() / 2)));
            out.addAll(limit(longTermMemorySnippets, policy.getMaxLongTermMemoryItems()));
        }
        if (out.isEmpty()) {
            out.add("memory_hints: skipped by node template policy");
        }
        return out.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(24)
                .toList();
    }

    private List<String> buildSummaryInput(SummaryResult roundSummaryInput) {
        if (roundSummaryInput == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (roundSummaryInput.getNarrativeSummary() != null && !roundSummaryInput.getNarrativeSummary().isBlank()) {
            out.add("narrative_summary=" + roundSummaryInput.getNarrativeSummary());
        }
        if (roundSummaryInput.getStateSnapshot() != null && !roundSummaryInput.getStateSnapshot().isEmpty()) {
            out.add("state_snapshot=" + safe(roundSummaryInput.getStateSnapshot()));
        }
        return out;
    }

    private List<String> buildOutputConstraints(ContextNodeTemplatePolicy policy,
                                                StructuredContextPackage contextPackage,
                                                InputReconstructionResult reconstructionResult,
                                                ToolSemanticResult toolSemanticResult,
        SummaryResult roundSummaryInput) {
        List<String> constraints = new ArrayList<>();
        constraints.add("Single-line JSON output only.");
        constraints.add("Must contain fields: " + String.join(", ", resolveNodeOutputFields(policy)) + ".");
        constraints.add("Preserve confirmed constraints and latest tool conclusions.");
        String nodeType = policy == null ? "" : safe(policy.getNodeType()).toUpperCase();
        String nodeKind = policy == null ? "" : safe(policy.getNodeKind()).toUpperCase();
        String templateKey = policy == null ? "" : safe(policy.getTemplateKey());
        if (!templateKey.isBlank()) {
            constraints.add("Node template key: " + templateKey);
        }
        if ("TOOL".equals(nodeKind) || "RESOURCE".equals(nodeKind) || "WORKFLOW".equals(nodeKind)) {
            constraints.add("Node kind " + nodeKind + ": prioritize executable facts, params, and verification signals.");
        } else if ("PROMPT".equals(nodeKind)) {
            constraints.add("Node kind PROMPT: emphasize prompt/resource hints and avoid fabricated tool outcomes.");
        } else if ("VALIDATE".equals(nodeKind)) {
            constraints.add("Node kind VALIDATE: output must include explicit pass/fail rationale and unresolved risks.");
        } else if ("REPORT".equals(nodeKind) || "CODE".equals(nodeKind) || "ANALYZE".equals(nodeKind)) {
            constraints.add("Node kind " + nodeKind + ": keep structured conclusions with evidence-backed claims.");
        }
        if ("PLANNING".equals(nodeType) || "REPLANNING".equals(nodeType)) {
            constraints.add("Planning node: prioritize explicit goal decomposition and unresolved slots.");
        } else if ("EXECUTING".equals(nodeType) || "CONTEXT_BUILDING".equals(nodeType) || "REFLECTING".equals(nodeType)) {
            constraints.add("Execution node: ground response in tool semantic facts and selected evidence.");
        } else if ("WAITING_APPROVAL".equals(nodeType) || "WAITING_TOOL".equals(nodeType) || "WAITING_USER".equals(nodeType)) {
            constraints.add("Waiting node: do not fabricate progress; clearly state pending action.");
        } else if ("REPORTING".equals(nodeType) || "COMPLETED".equals(nodeType)) {
            constraints.add("Reporting node: summarize completed outcomes, failures, and next actionable step.");
        }
        if (contextPackage != null && contextPackage.getPromptPolicy() != null) {
            Object synthesisMode = contextPackage.getPromptPolicy().get("synthesis_mode");
            if (synthesisMode != null && !String.valueOf(synthesisMode).isBlank()) {
                constraints.add("Synthesis policy: " + synthesisMode);
            }
        }
        if (reconstructionResult != null && reconstructionResult.getMissingSlots() != null && !reconstructionResult.getMissingSlots().isEmpty()) {
            constraints.add("Unresolved slots must stay explicit: " + reconstructionResult.getMissingSlots());
        }
        if (toolSemanticResult != null && toolSemanticResult.getUnresolvedIssues() != null && !toolSemanticResult.getUnresolvedIssues().isEmpty()) {
            constraints.add("Tool unresolved issues must not be omitted: " + toolSemanticResult.getUnresolvedIssues());
        }
        if (roundSummaryInput != null && roundSummaryInput.getStateSnapshot() != null && !roundSummaryInput.getStateSnapshot().isEmpty()) {
            constraints.add("Round summary snapshot must remain semantically consistent with output.");
        }
        return constraints;
    }

    private List<String> resolveNodeOutputFields(ContextNodeTemplatePolicy policy) {
        String nodeType = policy == null ? "" : safe(policy.getNodeType()).toUpperCase();
        String nodeKind = policy == null ? "" : safe(policy.getNodeKind()).toUpperCase();
        if ("TOOL".equals(nodeKind) || "RESOURCE".equals(nodeKind) || "WORKFLOW".equals(nodeKind)) {
            return List.of("action", "target", "arguments", "verification", "reply");
        }
        if ("VALIDATE".equals(nodeKind)) {
            return List.of("result", "rationale", "risks", "reply");
        }
        if ("REPORT".equals(nodeKind) || "CODE".equals(nodeKind) || "ANALYZE".equals(nodeKind)) {
            return List.of("summary", "evidence", "next_step", "reply");
        }
        if ("PLANNING".equals(nodeType) || "REPLANNING".equals(nodeType)) {
            return List.of("plan", "open_questions", "next_step", "reply");
        }
        if ("WAITING_APPROVAL".equals(nodeType) || "WAITING_TOOL".equals(nodeType) || "WAITING_USER".equals(nodeType)) {
            return List.of("pending", "reason", "next_step", "reply");
        }
        return List.of("thought", "emotion", "reply");
    }

    private OnDemandMemoryPayload resolveOnDemandMemory(String sessionId,
                                                        StructuredContextPackage contextPackage,
                                                        InputReconstructionResult reconstructionResult,
                                                        ContextNodeTemplatePolicy policy,
                                                        List<String> workingMemorySnippets,
                                                        List<String> retrievedMemorySnippets,
                                                        List<String> knowledgeSnippets,
                                                        List<String> preferenceSnippets,
                                                        List<String> longTermMemorySnippets,
                                                        List<EvidenceBlock> knowledgeEvidenceBlocks) {
        if (sessionId == null || sessionId.isBlank() || contextPackage == null) {
            return OnDemandMemoryPayload.empty();
        }
        boolean needTaskMemory = shouldFetchTaskMemory(
                policy,
                workingMemorySnippets,
                retrievedMemorySnippets,
                knowledgeSnippets,
                longTermMemorySnippets,
                knowledgeEvidenceBlocks
        );
        boolean needRelationalMemory = shouldFetchRelationalMemory(policy, preferenceSnippets, longTermMemorySnippets);
        if (!needTaskMemory && !needRelationalMemory) {
            return OnDemandMemoryPayload.empty();
        }

        String semanticQuery = buildOnDemandSemanticQuery(reconstructionResult, contextPackage, policy);
        Map<String, Object> taskContext = needTaskMemory
                ? safeMap(taskMemoryRetriever.retrieve(sessionId, semanticQuery, contextPackage.getTaskState()))
                : Map.of();
        Map<String, Object> relationalContext = needRelationalMemory
                ? safeMap(relationalMemoryRetriever.retrieve(sessionId, semanticQuery, contextPackage.getRelationalState()))
                : Map.of();

        List<String> working = extractWorkingMemorySnippets(taskContext);
        List<String> retrieved = mergeDistinct(
                extractPerceptualBufferSnippets(taskContext.get("task_perceptual_buffer"), "task_buffer"),
                extractPerceptualBufferSnippets(relationalContext.get("relational_perceptual_buffer"), "relation_buffer")
        );
        List<String> knowledge = extractKnowledgeSnippets(taskContext);
        List<String> preferences = extractRelationalPreferenceSnippets(relationalContext);
        List<String> longTerm = mergeDistinct(
                extractTaskLongTermSnippets(taskContext),
                extractRelationalLongTermSnippets(relationalContext)
        );
        return new OnDemandMemoryPayload(working, retrieved, knowledge, preferences, longTerm);
    }

    private boolean shouldFetchTaskMemory(ContextNodeTemplatePolicy policy,
                                          List<String> workingMemorySnippets,
                                          List<String> retrievedMemorySnippets,
                                          List<String> knowledgeSnippets,
                                          List<String> longTermMemorySnippets,
                                          List<EvidenceBlock> knowledgeEvidenceBlocks) {
        if (policy == null) {
            return isEmpty(workingMemorySnippets) || isEmpty(knowledgeSnippets);
        }
        if (policy.isIncludeWorkingMemory() && isEmpty(workingMemorySnippets)) {
            return true;
        }
        if (policy.isIncludeRetrievedMemory() && isEmpty(retrievedMemorySnippets)) {
            return true;
        }
        if (policy.isIncludeLongTermMemory() && isEmpty(longTermMemorySnippets)) {
            return true;
        }
        return isEmpty(knowledgeEvidenceBlocks) && isEmpty(knowledgeSnippets);
    }

    private boolean shouldFetchRelationalMemory(ContextNodeTemplatePolicy policy,
                                                List<String> preferenceSnippets,
                                                List<String> longTermMemorySnippets) {
        if (policy == null) {
            return isEmpty(preferenceSnippets);
        }
        if (policy.isIncludeLongTermMemory() && isEmpty(preferenceSnippets)) {
            return true;
        }
        return policy.isIncludeRetrievedMemory() && isEmpty(longTermMemorySnippets);
    }

    private String buildOnDemandSemanticQuery(InputReconstructionResult reconstructionResult,
                                              StructuredContextPackage contextPackage,
                                              ContextNodeTemplatePolicy policy) {
        String explicitGoal = reconstructionResult == null ? "" : safe(reconstructionResult.getExplicitTaskGoal());
        String normalizedIntent = reconstructionResult == null ? "" : safe(reconstructionResult.getNormalizedUserIntent());
        TaskState taskStateEntity = contextPackage == null ? null : contextPackage.getTaskStateEntity();
        String stateGoal = taskStateEntity == null ? "" : safe(taskStateEntity.getObjective());
        String currentNode = taskStateEntity == null ? "" : safe(taskStateEntity.getCurrentNode());
        String retrievalIntent = contextPackage == null || contextPackage.getRetrievalState() == null
                ? ""
                : safe(contextPackage.getRetrievalState().getReconstructedIntent());
        String goalSeed = firstNonBlank(explicitGoal, firstNonBlank(normalizedIntent, stateGoal));
        if (goalSeed.isBlank()) {
            goalSeed = "goal_unavailable";
        }
        return "goal=" + goalSeed
                + " | node=" + firstNonBlank(currentNode, safe(policy == null ? null : policy.getNodeType()))
                + " | retrieval_intent=" + retrievalIntent
                + " | stage=" + safe(contextPackage == null || contextPackage.getTaskState() == null ? null : contextPackage.getTaskState().name())
                + " | query_source=governed_structured_signal";
    }

    private List<String> extractWorkingMemorySnippets(Map<String, Object> taskContext) {
        Map<String, Object> working = mapOf(taskContext.get("working_memory"));
        if (working.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("working.goal_raw: " + safe(working.get("goal_raw")));
        out.add("working.goal_refined: " + safe(working.get("goal_refined")));
        out.add("working.unresolved_questions: " + safe(working.get("unresolved_questions_json")));
        out.add("working.risks: " + safe(working.get("risks_json")));
        out.add("working.active_node_id: " + safe(working.get("active_node_id")));
        return out.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private List<String> extractTaskLongTermSnippets(Map<String, Object> taskContext) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : listOfMap(taskContext.get("task_facts"))) {
            out.add("task_fact: " + safe(item.get("fact_key")) + "=" + safe(item.get("fact_value_text")));
        }
        for (Map<String, Object> item : listOfMap(taskContext.get("task_episodes"))) {
            out.add("task_episode: " + safe(item.get("episode_type")) + " | " + safe(item.get("trajectory_summary")));
        }
        for (Map<String, Object> item : listOfMap(taskContext.get("task_procedures"))) {
            out.add("task_procedure: " + safe(item.get("name")) + " | " + safe(item.get("description")));
        }
        return out.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(20).toList();
    }

    private List<String> extractRelationalLongTermSnippets(Map<String, Object> relationalContext) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : listOfMap(relationalContext.get("episodes"))) {
            out.add("relation_episode: " + safe(item.get("episode_type")) + " | " + safe(item.get("summary")));
        }
        for (Map<String, Object> item : listOfMap(relationalContext.get("procedures"))) {
            out.add("relation_procedure: " + safe(item.get("name")) + " | " + safe(item.get("description")));
        }
        return out.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(20).toList();
    }

    private List<String> extractRelationalPreferenceSnippets(Map<String, Object> relationalContext) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : listOfMap(relationalContext.get("semantic_facts"))) {
            out.add("relation_pref: " + safe(item.get("fact_key")) + "=" + safe(item.get("fact_value_text")));
        }
        return out.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(16).toList();
    }

    private List<String> extractKnowledgeSnippets(Map<String, Object> taskContext) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : listOfMap(taskContext.get("knowledge"))) {
            out.add("title: " + safe(item.get("title")) + "\ncontent: " + safe(firstNonBlank(safe(item.get("chunk_text")), safe(item.get("content")))));
        }
        return out.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(12).toList();
    }

    private List<String> extractPerceptualBufferSnippets(Object value, String prefix) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> item : listOfMap(value)) {
            String main = firstNonBlank(safe(item.get("signal_json")), firstNonBlank(safe(item.get("emotion_signal_json")), safe(item.get("message_ref"))));
            if (!main.isBlank()) {
                out.add(prefix + ": " + main);
            }
        }
        return out.stream().filter(item -> item != null && !item.isBlank()).distinct().limit(12).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMap(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : Collections.emptyList();
    }

    private boolean isEmpty(List<?> value) {
        return value == null || value.isEmpty();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private List<String> limit(List<String> input, int maxItems) {
        if (input == null || input.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        return input.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(maxItems)
                .toList();
    }

    private List<String> mergeDistinct(List<String> left, List<String> right) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return new ArrayList<>(merged);
    }

    private String toPrompt(Map<String, List<String>> sections, String runtimePromptInput, String runtimeTemplate) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n");
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                for (String line : entry.getValue()) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    sb.append(line).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("## Runtime Prompt\n");
        String runtimePrompt = runtimeTemplate == null || runtimeTemplate.isBlank()
                ? PromptTemplates.RUNTIME_PROMPT
                : runtimeTemplate;
        sb.append(runtimePrompt.formatted(runtimePromptInput == null ? "" : runtimePromptInput.trim()));
        return sb.toString();
    }

    private PromptResolveResult resolvePromptAssembly(String userInput,
                                                      StructuredContextPackage contextPackage,
                                                      ContextNodeTemplatePolicy policy) {
        if (promptResolverService == null) {
            return null;
        }
        try {
            PromptResolveContext context = PromptResolveContext.builder()
                    .sessionId(contextPackage == null ? "" : safe(contextPackage.getSessionId()))
                    .userInput(userInput)
                    .policyId(resolvePolicyId(contextPackage))
                    .personaId(resolveContextBinding(contextPackage, "personaId", "persona_id"))
                    .sceneId(resolveContextBinding(contextPackage, "sceneId", "scene_id"))
                    .agent(resolvePromptAgent(policy))
                    .nodeKind(policy == null ? "" : safe(policy.getNodeKind()))
                    .taskState(contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name())
                    .modelFamily(resolveModelFamily(contextPackage))
                    .build();
            return promptResolverService.resolve(context);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String resolvePromptAgent(ContextNodeTemplatePolicy policy) {
        if (policy != null && policy.getPromptAgent() != null && !policy.getPromptAgent().isBlank()) {
            return policy.getPromptAgent();
        }
        return "MAIN_CHAT_AGENT";
    }

    private String resolvePolicyId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getPromptPolicy() == null) {
            return "";
        }
        Object byCamel = contextPackage.getPromptPolicy().get("policyId");
        if (byCamel != null && !String.valueOf(byCamel).isBlank()) {
            return String.valueOf(byCamel);
        }
        Object bySnake = contextPackage.getPromptPolicy().get("policy_id");
        return bySnake == null ? "" : String.valueOf(bySnake);
    }

    private String resolveModelFamily(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return "";
        }
        Object model = contextPackage.getRuntime().get("modelFamily");
        if (model != null && !String.valueOf(model).isBlank()) {
            return String.valueOf(model);
        }
        model = contextPackage.getRuntime().get("model_family");
        return model == null ? "" : String.valueOf(model);
    }

    private PromptValueSelection resolveSystemPromptSelection(PromptResolveResult resolveResult) {
        String fallback = promptRegistryService == null
                ? PromptTemplates.SYSTEM_PROMPT
                : promptRegistryService.resolvePromptValue("system.base_v1", PromptTemplates.SYSTEM_PROMPT);
        if (resolveResult == null || resolveResult.getSlotMapping() == null) {
            return PromptValueSelection.of(fallback, buildFallbackRef("system.base_v1", "instructions.system"));
        }
        List<ResolvedPromptItem> items = resolveResult.getSlotMapping().getOrDefault("instructions.system", List.of());
        if (items.isEmpty()) {
            return PromptValueSelection.of(fallback, buildFallbackRef("system.base_v1", "instructions.system"));
        }
        String resolved = items.stream()
                .map(ResolvedPromptItem::getValue)
                .filter(value -> value != null && !value.isBlank())
                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n\n" + b);
        if (resolved.isBlank()) {
            return PromptValueSelection.of(fallback, buildFallbackRef("system.base_v1", "instructions.system"));
        }
        return PromptValueSelection.of(resolved, Map.of());
    }

    private PromptValueSelection resolveRuntimePromptTemplateSelection(PromptResolveResult resolveResult) {
        String fallback = promptRegistryService == null
                ? PromptTemplates.RUNTIME_PROMPT
                : promptRegistryService.resolvePromptValue("runtime.main_v1", PromptTemplates.RUNTIME_PROMPT);
        if (resolveResult == null || resolveResult.getSlotMapping() == null) {
            return PromptValueSelection.of(fallback, buildFallbackRef("runtime.main_v1", "runtime.prompt"));
        }
        List<ResolvedPromptItem> items = resolveResult.getSlotMapping().getOrDefault("runtime.prompt", List.of());
        if (items.isEmpty()) {
            return PromptValueSelection.of(fallback, buildFallbackRef("runtime.main_v1", "runtime.prompt"));
        }
        String resolved = items.stream()
                .map(ResolvedPromptItem::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        if (resolved.isBlank()) {
            return PromptValueSelection.of(fallback, buildFallbackRef("runtime.main_v1", "runtime.prompt"));
        }
        return PromptValueSelection.of(resolved, Map.of());
    }

    private Map<String, Object> buildPromptAssemblyMeta(PromptResolveResult resolveResult,
                                                        List<Map<String, Object>> fallbackRefs,
                                                        String policyId) {
        if (promptSnapshotBridgeService != null) {
            Map<String, Object> payload = promptSnapshotBridgeService.buildSnapshotPayload(resolveResult, policyId);
            if (fallbackRefs == null || fallbackRefs.isEmpty()) {
                return payload == null ? Map.of() : payload;
            }
            Map<String, Object> merged = new LinkedHashMap<>();
            if (payload != null && !payload.isEmpty()) {
                merged.putAll(payload);
            }
            List<Map<String, Object>> refs = new ArrayList<>();
            Object promptRefs = merged.get("promptRefs");
            if (promptRefs instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> map && !map.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) map;
                        refs.add(casted);
                    }
                }
            }
            refs.addAll(fallbackRefs.stream()
                    .filter(item -> item != null && !item.isEmpty())
                    .toList());
            if (!refs.isEmpty()) {
                merged.put("promptRefs", refs);
            }
            return merged.isEmpty() ? Map.of() : merged;
        }

        List<Map<String, Object>> refs = new ArrayList<>();
        Map<String, List<Map<String, Object>>> slotMapping = new LinkedHashMap<>();
        if (resolveResult != null && resolveResult.getMatchedItems() != null) {
            refs.addAll(resolveResult.getMatchedItems().stream().map(this::toPromptRefRow).toList());
        }
        if (resolveResult != null && resolveResult.getSlotMapping() != null) {
            for (Map.Entry<String, List<ResolvedPromptItem>> entry : resolveResult.getSlotMapping().entrySet()) {
                String slot = safe(entry.getKey());
                if (slot.isBlank()) {
                    continue;
                }
                List<Map<String, Object>> items = entry.getValue() == null
                        ? List.of()
                        : entry.getValue().stream().map(this::toPromptRefRow).toList();
                slotMapping.put(slot, items);
            }
        }
        if (fallbackRefs != null) {
            refs.addAll(fallbackRefs.stream()
                    .filter(item -> item != null && !item.isEmpty())
                    .toList());
        }
        if (refs.isEmpty()) {
            return Map.of();
        }
        return Map.of(
                "policyId", policyId == null || policyId.isBlank()
                        ? (resolveResult == null || resolveResult.getPolicyId() == null ? "" : resolveResult.getPolicyId())
                        : policyId,
                "assemblerVersion", "assembler.v1",
                "promptRefs", refs,
                "slotMapping", slotMapping
        );
    }

    private Map<String, Object> toPromptRefRow(ResolvedPromptItem item) {
        if (item == null) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", item.getItemId());
        row.put("versionId", item.getVersionId());
        row.put("key", item.getKey());
        row.put("version", item.getVersion());
        row.put("runtimeSlot", item.getRuntimeSlot());
        row.put("matchReason", item.getMatchReason());
        row.put("category", item.getCategory());
        row.put("value", item.getValue());
        return row;
    }

    private Map<String, Object> buildFallbackRef(String key, String runtimeSlot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("runtimeSlot", runtimeSlot);
        row.put("matchReason", "FALLBACK");
        if (promptRegistryService != null) {
            promptRegistryService.getByKey(key).ifPresent(record -> {
                row.put("itemId", record.getItemId());
                row.put("versionId", record.getVersionId());
                row.put("version", record.getVersion());
                row.put("category", record.getCategory());
                row.put("value", record.getValue());
            });
        }
        row.putIfAbsent("itemId", null);
        row.putIfAbsent("versionId", null);
        row.putIfAbsent("version", "");
        row.putIfAbsent("category", "");
        row.putIfAbsent("value", "");
        return row;
    }

    private record PromptValueSelection(String value, Map<String, Object> ref) {
        private static PromptValueSelection of(String value, Map<String, Object> ref) {
            return new PromptValueSelection(value == null ? "" : value, ref == null ? Map.of() : ref);
        }
    }

    private String resolveContextBinding(StructuredContextPackage contextPackage, String camelKey, String snakeKey) {
        if (contextPackage == null) {
            return "";
        }
        String value = readFromMap(contextPackage.getPromptPolicy(), camelKey, snakeKey);
        if (!value.isBlank()) {
            return value;
        }
        value = readFromMap(contextPackage.getTaskContext(), camelKey, snakeKey);
        if (!value.isBlank()) {
            return value;
        }
        return readFromMap(contextPackage.getRelationalContext(), camelKey, snakeKey);
    }

    private String readFromMap(Map<String, Object> map, String camelKey, String snakeKey) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        Object camel = map.get(camelKey);
        if (camel != null && !String.valueOf(camel).isBlank()) {
            return String.valueOf(camel);
        }
        Object snake = map.get(snakeKey);
        if (snake != null && !String.valueOf(snake).isBlank()) {
            return String.valueOf(snake);
        }
        return "";
    }

    private String buildRuntimePromptInput(String userInput, InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null) {
            return "raw_input_archived=true; raw_input_fact_ref=" + rawInputFactRef(userInput)
                    + "; use_reconstructed_intent_section_as_primary_input";
        }
        return "normalizedIntent=" + safe(reconstructionResult.getNormalizedUserIntent())
                + "; explicitTaskGoal=" + safe(reconstructionResult.getExplicitTaskGoal())
                + "; clarifiedEntities=" + safe(reconstructionResult.getClarifiedEntities())
                + "; businessConstraints=" + safe(reconstructionResult.getBusinessConstraints())
                + "; timeScope=" + safe(reconstructionResult.getTimeScope())
                + "; missingSlots=" + safe(reconstructionResult.getMissingSlots())
                + "; intentConfidence=" + reconstructionResult.getIntentConfidence()
                + "; rawInputArchived=true"
                + "; rawInputLength=" + (userInput == null ? 0 : userInput.trim().length())
                + "; rawInputFactRef=" + rawInputFactRef(userInput);
    }

    private List<String> buildRawInputFact(String userInput) {
        String raw = userInput == null ? "" : userInput.trim();
        if (raw.isBlank()) {
            return List.of("raw_input_fact={archived=true; length=0; hash=0; content=}");
        }
        String compact = raw.replaceAll("\\s+", " ").trim();
        if (compact.length() > 240) {
            compact = compact.substring(0, 240);
        }
        return List.of("raw_input_fact={archived=true; length=" + raw.length()
                + "; hash=" + Integer.toHexString(raw.hashCode())
                + "; content=" + compact + "}");
    }

    private String rawInputFactRef(String userInput) {
        String raw = userInput == null ? "" : userInput.trim();
        return "hash=" + Integer.toHexString(raw.hashCode()) + ",len=" + raw.length();
    }

    private List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, List<String>> toCanonicalSections(Map<String, List<String>> sections) {
        if (sections == null || sections.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> canonical = new LinkedHashMap<>();
        canonical.put("Instructions", sections.getOrDefault("Instructions", List.of()));
        canonical.put("Current Task State", sections.getOrDefault("Current Task State", List.of()));
        canonical.put("Reconstructed User Intent", sections.getOrDefault("Reconstructed User Intent", List.of()));
        canonical.put("Relevant Knowledge Evidence", sections.getOrDefault("Relevant Knowledge Evidence", List.of()));
        canonical.put("MCP Resource / Prompt Hints", mergeDistinct(
                mergeDistinct(
                        sections.getOrDefault("MCP Resource / Prompt Hints", List.of()),
                        sections.getOrDefault("MCP Prompt Hints", List.of())
                ),
                mergeDistinct(
                        sections.getOrDefault("MCP Resource Hints", List.of()),
                        sections.getOrDefault("MCP Workflow Hints", List.of())
                )
        ));
        canonical.put("Tool Evidence", sections.getOrDefault("Tool Evidence", List.of()));
        canonical.put("Recent Interaction Context", sections.getOrDefault("Recent Interaction Context", List.of()));
        canonical.put("Memory Hints", sections.getOrDefault("Memory Hints", List.of()));
        canonical.put("Output Constraints", sections.getOrDefault("Output Constraints", List.of()));
        return canonical;
    }

    private Map<String, Integer> sectionBudget(Map<String, Integer> rawBudget, ContextNodeTemplatePolicy policy) {
        Map<String, Integer> mapped = new LinkedHashMap<>();
        mapped.put("Instructions", 800);
        mapped.put("Current Task State", rawBudget.getOrDefault("task_working", 1800));
        mapped.put("Reconstructed User Intent", rawBudget.getOrDefault("task_buffer", 900));
        mapped.put("Relevant Knowledge Evidence", rawBudget.getOrDefault("knowledge", 2500));
        int mcpBudget = rawBudget.getOrDefault("plan_node", 1200);
        int promptBudget = Math.max(180, (int) (mcpBudget * 0.30));
        int resourceBudget = Math.max(220, (int) (mcpBudget * 0.40));
        int workflowBudget = Math.max(180, mcpBudget - promptBudget - resourceBudget);
        String nodeKind = policy == null ? "" : safe(policy.getNodeKind()).toUpperCase();
        if ("WORKFLOW".equals(nodeKind) || "ANALYZE".equals(nodeKind)) {
            workflowBudget = Math.max(workflowBudget, Math.max(260, (int) (mcpBudget * 0.45)));
            resourceBudget = Math.max(200, (int) (mcpBudget * 0.30));
            promptBudget = Math.max(160, mcpBudget - workflowBudget - resourceBudget);
        } else if ("TOOL".equals(nodeKind) || "RESOURCE".equals(nodeKind)) {
            resourceBudget = Math.max(resourceBudget, Math.max(280, (int) (mcpBudget * 0.50)));
            workflowBudget = Math.max(140, (int) (mcpBudget * 0.20));
            promptBudget = Math.max(140, mcpBudget - resourceBudget - workflowBudget);
        }
        mapped.put("MCP Resource / Prompt Hints", promptBudget + resourceBudget + workflowBudget);
        mapped.put("Tool Evidence", rawBudget.getOrDefault("task_procedures", 1400));
        mapped.put("Recent Interaction Context", rawBudget.getOrDefault("recent_messages", 1200));
        mapped.put("Memory Hints", rawBudget.getOrDefault("task_facts", 1300));
        mapped.put("Output Constraints", 220);
        if (policy != null && policy.getSectionBudgetOverrides() != null) {
            for (Map.Entry<String, Integer> entry : policy.getSectionBudgetOverrides().entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    mapped.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return mapped;
    }

    private record OnDemandMemoryPayload(List<String> workingMemorySnippets,
                                         List<String> retrievedMemorySnippets,
                                         List<String> knowledgeSnippets,
                                         List<String> preferenceSnippets,
                                         List<String> longTermMemorySnippets) {
        private static OnDemandMemoryPayload empty() {
            return new OnDemandMemoryPayload(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
