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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DefaultGlobalContextRerankAgent implements GlobalContextRerankAgent {

    @Override
    public ContextRerankResult rerank(InputReconstructionResult reconstructionResult,
                                      StructuredContextPackage contextPackage,
                                      RetrievalResponse retrievalResponse,
                                      List<Map<String, Object>> capabilityCandidates,
                                      TaskRuntimeState taskState) {
        List<Evidence> knowledge = selectEvidence(retrievalResponse, RetrievalSource.KNOWLEDGE, reconstructionResult, 8);
        List<Evidence> memory = selectEvidence(retrievalResponse, RetrievalSource.MEMORY, reconstructionResult, 6);
        List<Evidence> preference = selectEvidence(retrievalResponse, RetrievalSource.PREFERENCE, reconstructionResult, 3);
        List<List<String>> duplicateClusters = detectDuplicates(knowledge, memory, preference);

        List<String> selectedKnowledge = toKnowledgeBlocks(knowledge);
        List<String> selectedMemoryHints = toMemoryHints(memory, preference);
        List<Map<String, Object>> toolCandidates = selectCapabilityCandidates(capabilityCandidates, "TOOL", 8);
        List<Map<String, Object>> promptResourceCandidates = selectPromptResourceCandidates(capabilityCandidates, 6);
        List<String> rejected = collectRejected(capabilityCandidates, toolCandidates, promptResourceCandidates);
        Map<String, String> rationale = buildRationale(taskState, selectedKnowledge, selectedMemoryHints, toolCandidates, promptResourceCandidates);

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
                                          int limit) {
        if (response == null || response.getEvidences() == null) {
            return List.of();
        }
        List<Evidence> rows = response.getEvidences().getOrDefault(source, List.of());
        if (rows.isEmpty()) {
            return List.of();
        }
        String terms = reconstruction == null ? "" : safe(reconstruction.getNormalizedUserIntent()).toLowerCase(Locale.ROOT);
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
        if (evidence.getSource() == RetrievalSource.PREFERENCE) {
            score += 0.10;
        }
        return score;
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

    private List<String> toKnowledgeBlocks(List<Evidence> evidenceList) {
        if (evidenceList == null || evidenceList.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Evidence evidence : evidenceList) {
            String block = "id=" + safe(evidence.getId())
                    + "; title=" + safe(evidence.getTitle())
                    + "; content=" + safe(evidence.getContent());
            if (!block.isBlank()) {
                out.add(block);
            }
        }
        return new ArrayList<>(out);
    }

    private List<String> toMemoryHints(List<Evidence> memory, List<Evidence> preference) {
        List<String> out = new ArrayList<>();
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
        return out.stream().distinct().toList();
    }

    private List<Map<String, Object>> selectCapabilityCandidates(List<Map<String, Object>> rows, String type, int limit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(item -> type.equalsIgnoreCase(String.valueOf(item.getOrDefault("capability_type", ""))))
                .sorted(Comparator.comparingInt(this::riskPenalty))
                .limit(Math.max(limit, 1))
                .map(this::copyShallow)
                .toList();
    }

    private List<Map<String, Object>> selectPromptResourceCandidates(List<Map<String, Object>> rows, int limit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(item -> {
                    String type = String.valueOf(item.getOrDefault("capability_type", ""));
                    return "PROMPT".equalsIgnoreCase(type) || "RESOURCE".equalsIgnoreCase(type) || "WORKFLOW".equalsIgnoreCase(type);
                })
                .sorted(Comparator.comparingInt(this::riskPenalty))
                .limit(Math.max(limit, 1))
                .map(this::copyShallow)
                .toList();
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

    private Map<String, String> buildRationale(TaskRuntimeState taskState,
                                               List<String> knowledge,
                                               List<String> memory,
                                               List<Map<String, Object>> tools,
                                               List<Map<String, Object>> promptResources) {
        Map<String, String> rationale = new HashMap<>();
        rationale.put("task_stage", taskState == null ? "UNKNOWN" : taskState.name());
        rationale.put("knowledge", "selected=" + sizeOf(knowledge) + ", prioritize high-score evidence and semantic overlap");
        rationale.put("memory", "selected=" + sizeOf(memory) + ", keep recent and preference-consistent hints");
        rationale.put("tool", "selected=" + sizeOf(tools) + ", prioritize low-risk and stage-relevant capabilities");
        rationale.put("prompt_resource", "selected=" + sizeOf(promptResources) + ", keep compact hints for context budget");
        return rationale;
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

