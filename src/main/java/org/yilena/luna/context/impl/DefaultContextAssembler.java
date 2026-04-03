package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.SemanticPreservingPruner;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.PromptTemplates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultContextAssembler implements ContextAssembler {

    private final SemanticPreservingPruner semanticPreservingPruner;

    public DefaultContextAssembler(SemanticPreservingPruner semanticPreservingPruner) {
        this.semanticPreservingPruner = semanticPreservingPruner;
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
                                     ContextNodeTemplatePolicy nodeTemplatePolicy) {
        ContextNodeTemplatePolicy policy = nodeTemplatePolicy == null ? ContextNodeTemplatePolicy.defaultPolicy() : nodeTemplatePolicy;
        Map<String, List<String>> candidatePool = buildCandidatePool(
                rerankResult,
                knowledgeEvidenceBlocks,
                workingMemorySnippets,
                runtimeMemorySnippets,
                retrievedMemorySnippets,
                knowledgeSnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                executionCandidates,
                mcpResourceHints,
                toolSemanticResult,
                toolContext,
                policy
        );
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Instructions", lines(PromptTemplates.SYSTEM_PROMPT));
        sections.put("Current Task State", lines(buildCurrentTaskState(contextPackage, policy)));
        sections.put("Reconstructed User Intent", lines(buildReconstructedIntent(userInput, reconstructionResult)));
        sections.put("Relevant Knowledge Evidence", candidatePool.getOrDefault("knowledge", List.of()));
        sections.put("MCP Resource / Prompt Hints", candidatePool.getOrDefault("mcp", List.of()));
        sections.put("Tool Evidence", candidatePool.getOrDefault("tool", List.of()));
        sections.put("Recent Interaction Context", lines(buildRecentInteraction(contextPackage, runtimeMemorySnippets, policy)));
        sections.put("Memory Hints", candidatePool.getOrDefault("memory", List.of()));
        sections.put("Output Constraints", List.of(
                "Single-line JSON output only.",
                "Must contain fields: thought, emotion, reply.",
                "Preserve confirmed constraints and latest tool conclusions."
        ));

        SemanticPreservingPruner.PruneResult pruneResult = semanticPreservingPruner.prune(
                sections,
                sectionBudget(contextPackage == null ? Map.of() : contextPackage.getTokenBudgetPlan(), policy)
        );
        String prompt = toPrompt(pruneResult.getSections(), userInput);
        return AssembledContext.builder()
                .prompt(prompt)
                .sections(pruneResult.getSections())
                .sectionTokenCounts(pruneResult.getSectionTokenCounts())
                .sectionTokenRatios(pruneResult.getSectionTokenRatios())
                .build();
    }

    private Map<String, List<String>> buildCandidatePool(ContextRerankResult rerankResult,
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
                                                         ContextNodeTemplatePolicy policy) {
        Map<String, List<String>> pool = new LinkedHashMap<>();
        pool.put("knowledge", selectKnowledgeCandidates(rerankResult, knowledgeEvidenceBlocks, knowledgeSnippets));
        pool.put("mcp", selectMcpCandidates(rerankResult, executionCandidates, mcpResourceHints));
        pool.put("tool", lines(buildToolEvidence(toolContext, toolSemanticResult)));
        pool.put("memory", buildMemoryHints(
                workingMemorySnippets,
                runtimeMemorySnippets,
                retrievedMemorySnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                rerankResult,
                policy
        ));
        return pool;
    }

    private List<String> selectKnowledgeCandidates(ContextRerankResult rerankResult,
                                                   List<EvidenceBlock> knowledgeEvidenceBlocks,
                                                   List<String> knowledgeSnippets) {
        List<String> out = new ArrayList<>();
        if (rerankResult != null && rerankResult.getSelectedKnowledgeBlocks() != null && !rerankResult.getSelectedKnowledgeBlocks().isEmpty()) {
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

    private List<String> selectMcpCandidates(ContextRerankResult rerankResult,
                                             List<Resource> executionCandidates,
                                             List<String> mcpResourceHints) {
        List<String> out = new ArrayList<>();
        if (rerankResult != null) {
            if (rerankResult.getSelectedToolCandidates() != null) {
                rerankResult.getSelectedToolCandidates().forEach(row -> out.add("tool_candidate=" + safe(row)));
            }
            if (rerankResult.getSelectedPromptResources() != null) {
                rerankResult.getSelectedPromptResources().forEach(row -> out.add("prompt_or_resource=" + safe(row)));
            }
            if (rerankResult.getRationaleByNode() != null && !rerankResult.getRationaleByNode().isEmpty()) {
                out.add("rerank_rationale=" + safe(rerankResult.getRationaleByNode()));
            }
        }
        if (executionCandidates != null) {
            for (Resource candidate : executionCandidates) {
                if (candidate == null) {
                    continue;
                }
                out.add("execution_candidate="
                        + safe(candidate.getName())
                        + "|type=" + (candidate.getType() == null ? "" : candidate.getType().name())
                        + "|server=" + safe(candidate.getServerCode())
                        + "|approval=" + safe(candidate.getRequiresApproval()));
            }
        }
        if (mcpResourceHints != null) {
            out.addAll(mcpResourceHints.stream().map(item -> "mcp_hint=" + safe(item)).toList());
        }
        return out.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(14).toList();
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
            return userInput == null ? "" : userInput;
        }
        return "normalizedIntent=" + safe(reconstructionResult.getNormalizedUserIntent())
                + "; explicitGoal=" + safe(reconstructionResult.getExplicitTaskGoal())
                + "; entities=" + safe(reconstructionResult.getClarifiedEntities())
                + "; constraints=" + safe(reconstructionResult.getBusinessConstraints())
                + "; timeScope=" + safe(reconstructionResult.getTimeScope())
                + "; missingSlots=" + safe(reconstructionResult.getMissingSlots())
                + "; intentConfidence=" + reconstructionResult.getIntentConfidence();
    }

    private String buildToolEvidence(String toolContext, ToolSemanticResult toolSemanticResult) {
        String semantic = toolSemanticResult == null ? "" : safe(toolSemanticResult.getSemanticPayload());
        return "rawToolContext=" + safe(toolContext) + "\nsemanticToolContext=" + semantic;
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

    private List<String> limit(List<String> input, int maxItems) {
        if (input == null || input.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        return input.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(maxItems)
                .toList();
    }

    private String toPrompt(Map<String, List<String>> sections, String userInput) {
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
        sb.append(PromptTemplates.RUNTIME_PROMPT.formatted(userInput == null ? "" : userInput.trim()));
        return sb.toString();
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

    private Map<String, Integer> sectionBudget(Map<String, Integer> rawBudget, ContextNodeTemplatePolicy policy) {
        Map<String, Integer> mapped = new LinkedHashMap<>();
        mapped.put("Instructions", 800);
        mapped.put("Current Task State", rawBudget.getOrDefault("task_working", 1800));
        mapped.put("Reconstructed User Intent", rawBudget.getOrDefault("task_buffer", 900));
        mapped.put("Relevant Knowledge Evidence", rawBudget.getOrDefault("knowledge", 2500));
        mapped.put("MCP Resource / Prompt Hints", rawBudget.getOrDefault("plan_node", 1200));
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
}

