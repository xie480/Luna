package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.GlobalContextRerankAgent;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.utils.LlmClientUtil;

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
@RequiredArgsConstructor
public class DefaultGlobalContextRerankAgent implements GlobalContextRerankAgent {

    private static final String GLOBAL_RERANK_PROMPT = """
            You are Global Context Rerank Agent.
            Return strict JSON only:
            {
              "knowledgeRankIds":[],
              "memoryRankIds":[],
              "preferenceRankIds":[],
              "toolRankNames":[],
              "promptRankNames":[],
              "resourceRankNames":[],
              "workflowRankNames":[],
              "rationale":"..."
            }
            Rank candidates by node-goal fitness, stage relevance, and anti-noise.
            Do not invent ids or names.

            stage=%s
            nodeGoal=%s
            reconstructedIntent=%s
            knowledgeCandidates=%s
            memoryCandidates=%s
            preferenceCandidates=%s
            mcpCandidates=%s
            """;

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;

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
        int promptBudget = Math.max(100, (int) (mcpBudget * 0.30));
        int resourceBudget = Math.max(100, (int) (mcpBudget * 0.40));
        int workflowBudget = Math.max(100, mcpBudget - promptBudget - resourceBudget);
        if (taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING) {
            workflowBudget = Math.max(workflowBudget, Math.max(120, (int) (mcpBudget * 0.45)));
            resourceBudget = Math.max(80, (int) (mcpBudget * 0.30));
            promptBudget = Math.max(80, mcpBudget - workflowBudget - resourceBudget);
        } else if (taskState == TaskRuntimeState.EXECUTING || taskState == TaskRuntimeState.WAITING_TOOL) {
            resourceBudget = Math.max(resourceBudget, Math.max(120, (int) (mcpBudget * 0.50)));
            workflowBudget = Math.max(80, (int) (mcpBudget * 0.20));
            promptBudget = Math.max(80, mcpBudget - resourceBudget - workflowBudget);
        }
        String nodeGoal = resolveNodeGoal(reconstructionResult, contextPackage);
        String stage = taskState == null ? "UNKNOWN" : taskState.name();

        List<Evidence> knowledge = selectEvidence(retrievalResponse, RetrievalSource.KNOWLEDGE, reconstructionResult, nodeGoal, taskState, 16);
        List<Evidence> memory = selectEvidence(retrievalResponse, RetrievalSource.MEMORY, reconstructionResult, nodeGoal, taskState, 12);
        List<Evidence> preference = selectEvidence(retrievalResponse, RetrievalSource.PREFERENCE, reconstructionResult, nodeGoal, taskState, 8);
        ModelRerankResult modelRerank = tryModelRerank(reconstructionResult, stage, nodeGoal, capabilityCandidates, knowledge, memory, preference);
        knowledge = reorderByEvidenceIds(knowledge, modelRerank.knowledgeRankIds());
        memory = reorderByEvidenceIds(memory, modelRerank.memoryRankIds());
        preference = reorderByEvidenceIds(preference, modelRerank.preferenceRankIds());
        List<List<String>> duplicateClusters = detectDuplicates(knowledge, memory, preference);

        List<EvidenceBlock> selectedKnowledgeEvidenceBlocks = toKnowledgeEvidenceBlocks(knowledge, knowledgeBudget);
        List<String> selectedKnowledge = toKnowledgeSnippets(selectedKnowledgeEvidenceBlocks);
        List<String> selectedMemoryHints = toMemoryHints(memory, preference, memoryBudget);
        List<Map<String, Object>> toolCandidates = selectCapabilityCandidates(capabilityCandidates, "TOOL", 12, nodeGoal, taskState, mcpBudget, modelRerank.toolRankNames());
        List<Map<String, Object>> promptCandidates = selectCapabilityCandidates(capabilityCandidates, "PROMPT", 8, nodeGoal, taskState, promptBudget, modelRerank.promptRankNames());
        List<Map<String, Object>> resourceCandidates = selectCapabilityCandidates(capabilityCandidates, "RESOURCE", 8, nodeGoal, taskState, resourceBudget, modelRerank.resourceRankNames());
        List<Map<String, Object>> workflowCandidates = selectCapabilityCandidates(capabilityCandidates, "WORKFLOW", 8, nodeGoal, taskState, workflowBudget, modelRerank.workflowRankNames());
        List<String> rejected = collectRejected(capabilityCandidates, toolCandidates, promptCandidates, resourceCandidates, workflowCandidates);
        Map<String, String> rationale = buildRationale(stage, nodeGoal, totalBudget, selectedKnowledgeEvidenceBlocks, selectedMemoryHints, toolCandidates, promptCandidates, resourceCandidates, workflowCandidates);
        if (!modelRerank.rationale().isBlank()) {
            rationale.put("model_rationale", modelRerank.rationale());
        }

        return ContextRerankResult.builder()
                .selectedKnowledgeEvidenceBlocks(selectedKnowledgeEvidenceBlocks)
                .selectedKnowledgeBlocks(selectedKnowledge)
                .selectedToolCandidates(toolCandidates)
                .selectedPromptCandidates(promptCandidates)
                .selectedResourceCandidates(resourceCandidates)
                .selectedWorkflowCandidates(workflowCandidates)
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

    private List<EvidenceBlock> toKnowledgeEvidenceBlocks(List<Evidence> evidenceList, int tokenBudget) {
        if (evidenceList == null || evidenceList.isEmpty()) {
            return List.of();
        }
        List<EvidenceBlock> out = new ArrayList<>();
        int used = 0;
        for (Evidence evidence : evidenceList) {
            if (evidence == null) {
                continue;
            }
            String content = safe(evidence.getContent());
            String title = safe(evidence.getTitle());
            if (content.isBlank() && title.isBlank()) {
                continue;
            }
            String budgetProbe = "id=" + safe(evidence.getId()) + "; title=" + title + "; content=" + content;
            int estimate = estimateTokens(budgetProbe);
            if (used + estimate > tokenBudget && !out.isEmpty()) {
                break;
            }
            out.add(EvidenceBlock.builder()
                    .blockId(safe(evidence.getId()))
                    .sourceType(evidence.getSource() == null ? "" : evidence.getSource().value())
                    .title(title)
                    .content(content)
                    .score(evidence.getScore())
                    .metadata(evidence.getMetadata() == null ? Map.of() : new LinkedHashMap<>(evidence.getMetadata()))
                    .build());
            used += estimate;
        }
        return out;
    }

    private List<String> toKnowledgeSnippets(List<EvidenceBlock> evidenceBlocks) {
        if (evidenceBlocks == null || evidenceBlocks.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (EvidenceBlock block : evidenceBlocks) {
            if (block == null) {
                continue;
            }
            String line = "id=" + safe(block.getBlockId())
                    + "; source=" + safe(block.getSourceType())
                    + "; score=" + safe(block.getScore())
                    + "; title=" + safe(block.getTitle())
                    + "; content=" + safe(block.getContent());
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
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
                                                                 int tokenBudget,
                                                                 List<String> modelOrder) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> sorted = rows.stream()
                .filter(item -> type.equalsIgnoreCase(String.valueOf(item.getOrDefault("capability_type", ""))))
                .sorted(Comparator.comparingDouble((Map<String, Object> row) -> capabilityScore(row, nodeGoal, taskState)).reversed())
                .map(this::copyShallow)
                .toList();
        sorted = reorderByCapabilityName(sorted, modelOrder);
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
                                         List<Map<String, Object>> selectedPrompts,
                                         List<Map<String, Object>> selectedResources,
                                         List<Map<String, Object>> selectedWorkflows) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        Set<String> selected = new LinkedHashSet<>();
        if (selectedTools != null) {
            selected.addAll(selectedTools.stream().map(this::capabilityName).toList());
        }
        if (selectedPrompts != null) {
            selected.addAll(selectedPrompts.stream().map(this::capabilityName).toList());
        }
        if (selectedResources != null) {
            selected.addAll(selectedResources.stream().map(this::capabilityName).toList());
        }
        if (selectedWorkflows != null) {
            selected.addAll(selectedWorkflows.stream().map(this::capabilityName).toList());
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
                                               List<EvidenceBlock> knowledge,
                                               List<String> memory,
                                               List<Map<String, Object>> tools,
                                               List<Map<String, Object>> prompts,
                                               List<Map<String, Object>> resources,
                                               List<Map<String, Object>> workflows) {
        Map<String, String> rationale = new HashMap<>();
        rationale.put("task_stage", stage);
        rationale.put("node_goal", nodeGoal);
        rationale.put("token_budget", String.valueOf(totalBudget));
        rationale.put("knowledge", "selected=" + sizeOf(knowledge) + ", prioritize stage relevance and node-goal overlap");
        rationale.put("memory", "selected=" + sizeOf(memory) + ", keep unresolved issues and stable preferences");
        rationale.put("tool", "selected=" + sizeOf(tools) + ", prioritize low-risk and node-goal fit capabilities");
        rationale.put("prompt", "selected=" + sizeOf(prompts) + ", keep concise prompt templates by node-goal");
        rationale.put("resource", "selected=" + sizeOf(resources) + ", keep only resources with direct node utility");
        rationale.put("workflow", "selected=" + sizeOf(workflows) + ", keep workflow channel independent");
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

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private ModelRerankResult tryModelRerank(InputReconstructionResult reconstructionResult,
                                             String stage,
                                             String nodeGoal,
                                             List<Map<String, Object>> capabilityCandidates,
                                             List<Evidence> knowledge,
                                             List<Evidence> memory,
                                             List<Evidence> preference) {
        try {
            String promptTemplate = promptRegistryService == null
                    ? GLOBAL_RERANK_PROMPT
                    : promptRegistryService.resolvePromptValue("agent-local.rerank.default_v1", GLOBAL_RERANK_PROMPT);
            String prompt = promptTemplate.formatted(
                    stage,
                    nodeGoal == null ? "" : nodeGoal,
                    reconstructionResult == null ? "" : String.valueOf(reconstructionResult.getNormalizedUserIntent()),
                    summarizeEvidence(knowledge),
                    summarizeEvidence(memory),
                    summarizeEvidence(preference),
                    summarizeCapabilities(capabilityCandidates)
            );
            LlmRequest request = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(resolveSmallAgentModel())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.1)
                    .enablePromptInjectionCheck(false)
                    .build();
            LlmResponse response = llmClientUtil.generate(request);
            String content = response == null ? "" : response.getContent();
            if (content == null || content.isBlank()) {
                return ModelRerankResult.empty();
            }
            JsonNode node = objectMapper.readTree(stripFence(content));
            return new ModelRerankResult(
                    jsonArrayToList(node.path("knowledgeRankIds")),
                    jsonArrayToList(node.path("memoryRankIds")),
                    jsonArrayToList(node.path("preferenceRankIds")),
                    jsonArrayToList(node.path("toolRankNames")),
                    jsonArrayToList(node.path("promptRankNames")),
                    jsonArrayToList(node.path("resourceRankNames")),
                    jsonArrayToList(node.path("workflowRankNames")),
                    safe(node.path("rationale").asText(""))
            );
        } catch (Exception ignore) {
            return ModelRerankResult.empty();
        }
    }

    private List<Evidence> reorderByEvidenceIds(List<Evidence> items, List<String> modelOrder) {
        if (items == null || items.isEmpty() || modelOrder == null || modelOrder.isEmpty()) {
            return items == null ? List.of() : items;
        }
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < modelOrder.size(); i++) {
            order.put(modelOrder.get(i), i);
        }
        return items.stream()
                .sorted(Comparator.comparingInt(item -> order.getOrDefault(safe(item.getId()), Integer.MAX_VALUE)))
                .toList();
    }

    private List<Map<String, Object>> reorderByCapabilityName(List<Map<String, Object>> items, List<String> modelOrder) {
        if (items == null || items.isEmpty() || modelOrder == null || modelOrder.isEmpty()) {
            return items == null ? List.of() : items;
        }
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < modelOrder.size(); i++) {
            order.put(modelOrder.get(i), i);
        }
        return items.stream()
                .sorted(Comparator.comparingInt(item -> order.getOrDefault(capabilityName(item), Integer.MAX_VALUE)))
                .toList();
    }

    private String summarizeEvidence(List<Evidence> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> compact = rows.stream().limit(24).map(item -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", safe(item.getId()));
            map.put("title", safe(item.getTitle()));
            map.put("content", safe(item.getContent()));
            map.put("score", item.getScore());
            return map;
        }).toList();
        try {
            return objectMapper.writeValueAsString(compact);
        } catch (Exception ignore) {
            return "[]";
        }
    }

    private String summarizeCapabilities(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> compact = rows.stream().limit(40).map(row -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("capability_name", String.valueOf(row.getOrDefault("capability_name", "")));
            map.put("capability_type", String.valueOf(row.getOrDefault("capability_type", "")));
            map.put("description", String.valueOf(row.getOrDefault("description", "")));
            map.put("requires_approval", boolVal(row.get("requires_approval")));
            map.put("sensitivity", String.valueOf(row.getOrDefault("sensitivity", "LOW")));
            return map;
        }).toList();
        try {
            return objectMapper.writeValueAsString(compact);
        } catch (Exception ignore) {
            return "[]";
        }
    }

    private List<String> jsonArrayToList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText("");
            if (!value.isBlank()) {
                out.add(value);
            }
        });
        return out;
    }

    private String stripFence(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            value = value.replaceAll("(?s)```\\s*$", "");
        }
        return value.trim();
    }

    private String resolveSmallAgentModel() {
        if (geminiProperty != null && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null
                && !geminiProperty.getChat().getModelName().isBlank()) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty != null && geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null
                && !geminiProperty.getBig().getModelName().isBlank()) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
    }

    private record ModelRerankResult(List<String> knowledgeRankIds,
                                     List<String> memoryRankIds,
                                     List<String> preferenceRankIds,
                                     List<String> toolRankNames,
                                     List<String> promptRankNames,
                                     List<String> resourceRankNames,
                                     List<String> workflowRankNames,
                                     String rationale) {
        private static ModelRerankResult empty() {
            return new ModelRerankResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "");
        }
    }
}
