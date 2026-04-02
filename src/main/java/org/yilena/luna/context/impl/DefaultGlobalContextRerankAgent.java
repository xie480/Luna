package org.yilena.luna.context.impl;

import org.springframework.stereotype.Service;
import org.yilena.luna.context.GlobalContextRerankAgent;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DefaultGlobalContextRerankAgent implements GlobalContextRerankAgent {

    @Override
    public ContextRerankResult rerank(InputReconstructionResult reconstructionResult,
                                      StructuredContextPackage contextPackage,
                                      RetrievalResponse retrievalResponse,
                                      List<Map<String, Object>> capabilityCandidates,
                                      TaskRuntimeState taskState) {
        int totalBudget = resolveGlobalBudget(contextPackage);
        int knowledgeBudget = Math.max(320, (int) (totalBudget * 0.40));
        int memoryBudget = Math.max(220, (int) (totalBudget * 0.20));
        int mcpBudget = Math.max(260, (int) (totalBudget * 0.30));
        String nodeGoal = resolveNodeGoal(reconstructionResult, contextPackage);
        String stage = taskState == null ? "UNKNOWN" : taskState.name();

        List<Evidence> knowledge = selectEvidence(retrievalResponse, RetrievalSource.KNOWLEDGE, reconstructionResult, nodeGoal, taskState, 16);
        List<Evidence> memory = selectEvidence(retrievalResponse, RetrievalSource.MEMORY, reconstructionResult, nodeGoal, taskState, 12);
        List<Evidence> preference = selectEvidence(retrievalResponse, RetrievalSource.PREFERENCE, reconstructionResult, nodeGoal, taskState, 8);
        List<List<String>> duplicateClusters = detectDuplicates(knowledge, memory, preference);

        List<String> selectedKnowledge = toKnowledgeBlocks(knowledge, knowledgeBudget);
        List<String> selectedMemoryHints = toMemoryHints(memory, preference, memoryBudget);
        List<Map<String, Object>> toolCandidates = selectCapabilityCandidates(capabilityCandidates, "TOOL", 12, nodeGoal, taskState, mcpBudget);
        List<Map<String, Object>> promptResourceCandidates = selectPromptResourceCandidates(capabilityCandidates, 10, nodeGoal, taskState, mcpBudget);
        List<String> rejected = collectRejected(capabilityCandidates, toolCandidates, promptResourceCandidates);
        Map<String, String> rationale = buildRationale(stage, nodeGoal, totalBudget, selectedKnowledge, selectedMemoryHints, toolCandidates, promptResourceCandidates);

        return ContextRerankResult.builder()
                .selectedKnowledgeBlocks(selectedKnowledge)
                .selectedToolCandidates(toolCandidates)
                .selectedPromptResources(promptResourceCandidates)
                .selectedMemoryHints(selectedMemoryHints)
                .duplicateClusters(duplicateClusters)
                .rejectedCandidates(rejected)
                .rationaleByNode(rationale)
                .build();
    }

    private List<Evidence> selectEvidence(RetrievalResponse response,
                                          RetrievalSource source,
                                          InputReconstructionResult reconstruction,
                                          String nodeGoal,
                                          TaskRuntimeState taskState,
                                          int limit) {
        if (response == null || response.getEvidences() == null) {
            return List.of();
        }
        List<Evidence> rows = response.getEvidences().getOrDefault(source, List.of());
        if (rows.isEmpty()) {
            return List.of();
        }
        String terms = buildSemanticTerms(reconstruction, nodeGoal, taskState);
        return rows.stream()
                .sorted(Comparator.comparingDouble((Evidence item) -> score(item, terms)).reversed())
                .limit(Math.max(limit, 1))
                .toList();
    }

    private double score(Evidence evidence, String terms) {
        if (evidence == null) {
            return 0.0;
        }
        double score = evidence.getScore();
        String text = (safe(evidence.getTitle()) + " " + safe(evidence.getContent())).toLowerCase(Locale.ROOT);
        if (!terms.isBlank() && text.contains(terms)) {
            score += 0.35;
        } else if (!terms.isBlank() && hasTermOverlap(text, terms)) {
            score += 0.15;
        }
        score += Math.min(0.25, overlapCount(text, terms) * 0.03);
        if (evidence.getSource() == RetrievalSource.PREFERENCE) {
            score += 0.10;
        }
        return score;
    }

    private int overlapCount(String text, String terms) {
        String[] parts = terms.split("[,;|\\s]+");
        int hits = 0;
        for (String part : parts) {
            if (part.isBlank() || part.length() < 3) {
                continue;
            }
            if (text.contains(part)) {
                hits++;
            }
        }
        return hits;
    }

    private boolean hasTermOverlap(String text, String terms) {
        String[] parts = terms.split("[,;|\\s]+");
        int hits = 0;
        for (String part : parts) {
            if (part.isBlank() || part.length() < 3) {
                continue;
            }
            if (text.contains(part)) {
                hits++;
                if (hits >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<List<String>> detectDuplicates(List<Evidence>... groups) {
        Map<String, List<String>> bucket = new LinkedHashMap<>();
        for (List<Evidence> group : groups) {
            if (group == null) {
                continue;
            }
            for (Evidence evidence : group) {
                String signature = dedupeSignature(safe(evidence == null ? null : evidence.getContent()));
                if (signature.isBlank()) {
                    continue;
                }
                bucket.computeIfAbsent(signature, ignored -> new ArrayList<>())
                        .add(evidence == null ? "" : safe(evidence.getId()));
            }
        }
        return bucket.values().stream().filter(ids -> ids.size() > 1).toList();
    }

    private String dedupeSignature(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private List<String> toKnowledgeBlocks(List<Evidence> evidenceList, int tokenBudget) {
        if (evidenceList == null || evidenceList.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        int used = 0;
        for (Evidence evidence : evidenceList) {
            String block = "id=" + safe(evidence.getId())
                    + "; title=" + safe(evidence.getTitle())
                    + "; content=" + safe(evidence.getContent());
            if (!block.isBlank()) {
                int estimate = estimateTokens(block);
                if (used + estimate > tokenBudget && !out.isEmpty()) {
                    break;
                }
                out.add(block);
                used += estimate;
            }
        }
        return new ArrayList<>(out);
    }

    private List<String> toMemoryHints(List<Evidence> memory, List<Evidence> preference, int tokenBudget) {
        List<String> out = new LinkedList<>();
        if (memory != null) {
            out.addAll(memory.stream()
                    .map(item -> "memory:" + safe(item.getContent()))
                    .filter(s -> !s.isBlank())
                    .toList());
        }
        if (preference != null) {
            out.addAll(preference.stream()
                    .map(item -> "preference:" + safe(item.getContent()))
                    .filter(s -> !s.isBlank())
                    .toList());
        }
        List<String> deduped = out.stream().distinct().toList();
        int used = 0;
        List<String> selected = new ArrayList<>();
        for (String hint : deduped) {
            int estimate = estimateTokens(hint);
            if (used + estimate > tokenBudget && !selected.isEmpty()) {
                break;
            }
            selected.add(hint);
            used += estimate;
        }
        return selected;
    }

    private List<Map<String, Object>> selectCapabilityCandidates(List<Map<String, Object>> rows,
                                                                 String type,
                                                                 int limit,
                                                                 String nodeGoal,
                                                                 TaskRuntimeState taskState,
                                                                 int tokenBudget) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> sorted = rows.stream()
                .filter(item -> type.equalsIgnoreCase(String.valueOf(item.getOrDefault("capability_type", ""))))
                .sorted(Comparator.comparingDouble((Map<String, Object> row) -> capabilityScore(row, nodeGoal, taskState)).reversed())
                .map(this::copyShallow)
                .toList();
        return budgetedCapabilities(sorted, Math.max(limit, 1), tokenBudget);
    }

    private List<Map<String, Object>> selectPromptResourceCandidates(List<Map<String, Object>> rows,
                                                                     int limit,
                                                                     String nodeGoal,
                                                                     TaskRuntimeState taskState,
                                                                     int tokenBudget) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> sorted = rows.stream()
                .filter(item -> {
                    String type = String.valueOf(item.getOrDefault("capability_type", ""));
                    return "PROMPT".equalsIgnoreCase(type) || "RESOURCE".equalsIgnoreCase(type) || "WORKFLOW".equalsIgnoreCase(type);
                })
                .sorted(Comparator.comparingDouble((Map<String, Object> row) -> capabilityScore(row, nodeGoal, taskState)).reversed())
                .map(this::copyShallow)
                .toList();
        return budgetedCapabilities(sorted, Math.max(limit, 1), tokenBudget);
    }

    private List<Map<String, Object>> budgetedCapabilities(List<Map<String, Object>> sorted, int limit, int tokenBudget) {
        int used = 0;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : sorted) {
            int estimate = estimateTokens(String.valueOf(row));
            if (used + estimate > tokenBudget && !out.isEmpty()) {
                break;
            }
            out.add(row);
            used += estimate;
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private int riskPenalty(Map<String, Object> row) {
        String sensitivity = String.valueOf(row.getOrDefault("sensitivity", "LOW")).toUpperCase(Locale.ROOT);
        boolean requiresApproval = boolVal(row.get("requires_approval"));
        int score = switch (sensitivity) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
        return requiresApproval ? score + 2 : score;
    }

    private List<String> collectRejected(List<Map<String, Object>> all,
                                         List<Map<String, Object>> selectedTools,
                                         List<Map<String, Object>> selectedPromptResources) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        Set<String> selected = new LinkedHashSet<>();
        if (selectedTools != null) {
            selected.addAll(selectedTools.stream().map(this::capabilityName).toList());
        }
        if (selectedPromptResources != null) {
            selected.addAll(selectedPromptResources.stream().map(this::capabilityName).toList());
        }
        return all.stream()
                .map(this::capabilityName)
                .filter(name -> !name.isBlank() && !selected.contains(name))
                .limit(40)
                .toList();
    }

    private Map<String, String> buildRationale(String stage,
                                               String nodeGoal,
                                               int totalBudget,
                                               List<String> knowledge,
                                               List<String> memory,
                                               List<Map<String, Object>> tools,
                                               List<Map<String, Object>> promptResources) {
        Map<String, String> rationale = new HashMap<>();
        rationale.put("task_stage", stage);
        rationale.put("node_goal", nodeGoal);
        rationale.put("token_budget", String.valueOf(totalBudget));
        rationale.put("knowledge", "selected=" + sizeOf(knowledge) + ", prioritize stage relevance and node-goal overlap");
        rationale.put("memory", "selected=" + sizeOf(memory) + ", keep unresolved issues and stable preferences");
        rationale.put("tool", "selected=" + sizeOf(tools) + ", prioritize low-risk and node-goal fit capabilities");
        rationale.put("prompt_resource", "selected=" + sizeOf(promptResources) + ", keep compact cross-source hints for budget");
        return rationale;
    }

    private double capabilityScore(Map<String, Object> row, String nodeGoal, TaskRuntimeState taskState) {
        String capabilityName = String.valueOf(row.getOrDefault("capability_name", "")).toLowerCase(Locale.ROOT);
        String description = String.valueOf(row.getOrDefault("description", "")).toLowerCase(Locale.ROOT);
        String nodeTerms = nodeGoal == null ? "" : nodeGoal.toLowerCase(Locale.ROOT);
        double score = 1.0;
        if (!nodeTerms.isBlank() && (description.contains(nodeTerms) || capabilityName.contains(nodeTerms))) {
            score += 0.7;
        } else if (!nodeTerms.isBlank() && hasTermOverlap(description + " " + capabilityName, nodeTerms)) {
            score += 0.3;
        }
        score -= riskPenalty(row) * 0.15;
        String type = String.valueOf(row.getOrDefault("capability_type", "")).toUpperCase(Locale.ROOT);
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING) && "WORKFLOW".equals(type)) {
            score += 0.25;
        }
        if ((taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL) && "TOOL".equals(type)) {
            score += 0.25;
        }
        return score;
    }

    private String buildSemanticTerms(InputReconstructionResult reconstruction, String nodeGoal, TaskRuntimeState taskState) {
        List<String> terms = new ArrayList<>();
        if (reconstruction != null) {
            terms.add(safe(reconstruction.getExplicitTaskGoal()));
            terms.add(safe(reconstruction.getNormalizedUserIntent()));
            terms.add(safe(reconstruction.getTimeScope()));
            if (reconstruction.getBusinessConstraints() != null) {
                terms.addAll(reconstruction.getBusinessConstraints());
            }
        }
        terms.add(safe(nodeGoal));
        terms.add(taskState == null ? "UNKNOWN" : taskState.name());
        return terms.stream().filter(item -> item != null && !item.isBlank()).collect(Collectors.joining(" "));
    }

    private int resolveGlobalBudget(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTokenBudgetPlan() == null || contextPackage.getTokenBudgetPlan().isEmpty()) {
            return 4600;
        }
        int sum = contextPackage.getTokenBudgetPlan().values().stream().mapToInt(Integer::intValue).sum();
        return Math.max(2200, Math.min(sum, 7800));
    }

    private String resolveNodeGoal(InputReconstructionResult reconstructionResult, StructuredContextPackage contextPackage) {
        if (reconstructionResult != null && reconstructionResult.getExplicitTaskGoal() != null && !reconstructionResult.getExplicitTaskGoal().isBlank()) {
            return reconstructionResult.getExplicitTaskGoal();
        }
        if (contextPackage != null && contextPackage.getTaskStateEntity() != null) {
            String objective = contextPackage.getTaskStateEntity().getObjective();
            if (objective != null && !objective.isBlank()) {
                return objective;
            }
        }
        return "";
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private Map<String, Object> copyShallow(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }

    private String capabilityName(Map<String, Object> row) {
        return row == null ? "" : String.valueOf(row.getOrDefault("capability_name", ""));
    }

    private boolean boolVal(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
