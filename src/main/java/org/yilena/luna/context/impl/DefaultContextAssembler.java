package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.PromptTemplates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultContextAssembler implements ContextAssembler {

    @Override
    public AssembledContext assemble(StructuredContextPackage contextPackage,
                                     InputReconstructionResult reconstructionResult,
                                     ContextRerankResult rerankResult,
                                     ToolSemanticResult toolSemanticResult,
                                     String userInput,
                                     List<String> memorySnippets,
                                     List<String> knowledgeSnippets,
                                     List<String> preferenceSnippets,
                                     List<String> longTermMemorySnippets,
                                     String toolContext) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Instructions", lines(PromptTemplates.SYSTEM_PROMPT));
        sections.put("Current Task State", lines(buildCurrentTaskState(contextPackage)));
        sections.put("Reconstructed User Intent", lines(buildReconstructedIntent(userInput, reconstructionResult)));
        sections.put("Relevant Knowledge Evidence", lines(buildKnowledgeSection(knowledgeSnippets, rerankResult)));
        sections.put("MCP Resource / Prompt Hints", lines(buildMcpHintsSection(rerankResult)));
        sections.put("Tool Evidence", lines(buildToolEvidence(toolContext, toolSemanticResult)));
        sections.put("Recent Interaction Context", lines(buildRecentInteraction(contextPackage, memorySnippets)));
        sections.put("Memory Hints", lines(buildMemoryHints(memorySnippets, preferenceSnippets, longTermMemorySnippets, rerankResult)));
        sections.put("Output Constraints", List.of(
                "Single-line JSON output only.",
                "Must contain fields: thought, emotion, reply.",
                "Preserve confirmed constraints and latest tool conclusions."
        ));

        String prompt = toPrompt(sections, userInput);
        return AssembledContext.builder()
                .prompt(prompt)
                .sections(sections)
                .build();
    }

    private String buildCurrentTaskState(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return "taskState=UNKNOWN; relationalState=UNKNOWN";
        }
        return "taskState=" + (contextPackage.getTaskState() == null ? "UNKNOWN" : contextPackage.getTaskState().name())
                + "; relationalState=" + (contextPackage.getRelationalState() == null ? "UNKNOWN" : contextPackage.getRelationalState().name())
                + "; tokenBudget=" + safe(contextPackage.getTokenBudgetPlan());
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

    private String buildKnowledgeSection(List<String> knowledgeSnippets, ContextRerankResult rerankResult) {
        List<String> blocks = new ArrayList<>();
        if (rerankResult != null && rerankResult.getSelectedKnowledgeBlocks() != null) {
            blocks.addAll(rerankResult.getSelectedKnowledgeBlocks());
        }
        if (blocks.isEmpty() && knowledgeSnippets != null) {
            blocks.addAll(knowledgeSnippets);
        }
        return String.join("\n", blocks);
    }

    private String buildMcpHintsSection(ContextRerankResult rerankResult) {
        if (rerankResult == null) {
            return "";
        }
        return "tools=" + safe(rerankResult.getSelectedToolCandidates())
                + "; promptResources=" + safe(rerankResult.getSelectedPromptResources())
                + "; rationale=" + safe(rerankResult.getRationaleByNode());
    }

    private String buildToolEvidence(String toolContext, ToolSemanticResult toolSemanticResult) {
        String semantic = toolSemanticResult == null ? "" : safe(toolSemanticResult.getSemanticPayload());
        return "rawToolContext=" + safe(toolContext) + "\nsemanticToolContext=" + semantic;
    }

    @SuppressWarnings("unchecked")
    private String buildRecentInteraction(StructuredContextPackage contextPackage, List<String> memorySnippets) {
        List<String> lines = new ArrayList<>();
        if (contextPackage != null && contextPackage.getRecentMessages() != null) {
            List<Map<String, Object>> messages = contextPackage.getRecentMessages();
            int from = Math.max(0, messages.size() - 10);
            for (Map<String, Object> row : messages.subList(from, messages.size())) {
                lines.add(safe(row.get("role")) + ": " + safe(row.get("content_text")));
            }
        }
        if (lines.isEmpty() && memorySnippets != null) {
            lines.addAll(memorySnippets.stream().limit(10).toList());
        }
        return String.join("\n", lines);
    }

    private String buildMemoryHints(List<String> memorySnippets,
                                    List<String> preferenceSnippets,
                                    List<String> longTermMemorySnippets,
                                    ContextRerankResult rerankResult) {
        List<String> out = new ArrayList<>();
        if (rerankResult != null && rerankResult.getSelectedMemoryHints() != null) {
            out.addAll(rerankResult.getSelectedMemoryHints());
        }
        if (memorySnippets != null) {
            out.addAll(memorySnippets.stream().limit(8).toList());
        }
        if (preferenceSnippets != null) {
            out.addAll(preferenceSnippets.stream().limit(5).toList());
        }
        if (longTermMemorySnippets != null) {
            out.addAll(longTermMemorySnippets.stream().limit(5).toList());
        }
        return String.join("\n", out.stream().distinct().toList());
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
}

