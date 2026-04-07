package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.MasterPlanningService;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MasterPlanningServiceImpl implements MasterPlanningService {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;
    private final PromptRegistryService promptRegistryService;

    @Override
    public Map<String, Object> generateBlueprint(String planId,
                                                 String sessionId,
                                                 String reconstructedGoal,
                                                 InputReconstructionResult reconstructionResult,
                                                 List<Map<String, Object>> knowledgeEvidence,
                                                 List<Map<String, Object>> workflowHints) {
        List<Map<String, Object>> normalizedKnowledgeEvidence = normalizeSignalList(knowledgeEvidence, 12);
        List<Map<String, Object>> normalizedWorkflowHints = normalizeSignalList(workflowHints, 16);
        try {
            String effectiveGoal = reconstructedGoal == null ? "" : reconstructedGoal.trim();
            if (effectiveGoal.isBlank()) {
                throw new IllegalArgumentException("reconstructed goal is blank");
            }
            Map<String, Object> reconstructedIntent = toReconstructionPayload(reconstructionResult);
            String prompt = buildPlanningPrompt(
                    planId,
                    sessionId,
                    effectiveGoal,
                    reconstructedIntent,
                    normalizedKnowledgeEvidence,
                    normalizedWorkflowHints
            );

            LlmRequest req = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(resolvePlanningModelName())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.2)
                    .enablePromptInjectionCheck(false)
                    .build();

            LlmResponse resp = llmClientUtil.generate(req);
            String text = resp != null ? resp.getContent() : null;
            if (text == null || text.isBlank()) {
                log.warn("Master planner returned empty content, use fallback blueprint");
                return fallbackBlueprint(planId, sessionId, effectiveGoal, reconstructionResult, normalizedKnowledgeEvidence, normalizedWorkflowHints);
            }

            String cleaned = cleanJsonFence(text);
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
            map.putIfAbsent("planId", planId);
            map.putIfAbsent("sessionId", sessionId);
            map.putIfAbsent("userGoal", effectiveGoal);
            map.putIfAbsent("createdAt", LocalDateTime.now().toString());
            map.putIfAbsent("reconstructedIntent", reconstructedIntent);
            map.putIfAbsent("knowledgeEvidence", normalizedKnowledgeEvidence);
            map.putIfAbsent("workflowHints", normalizedWorkflowHints);
            return map;
        } catch (Exception e) {
            log.error("Master planner blueprint generation failed, use fallback", e);
            return fallbackBlueprint(planId, sessionId, reconstructedGoal, reconstructionResult, normalizedKnowledgeEvidence, normalizedWorkflowHints);
        }
    }

    private String resolvePlanningModelName() {
        if (geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null && !geminiProperty.getCode().getModelName().isBlank()) {
            return geminiProperty.getCode().getModelName();
        }
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null && !geminiProperty.getBig().getModelName().isBlank()) {
            return geminiProperty.getBig().getModelName();
        }
        throw new IllegalStateException("No planning model configured (gemini.code/gemini.big)");
    }

    private String buildPlanningPrompt(String planId,
                                       String sessionId,
                                       String effectiveGoal,
                                       Map<String, Object> planningMeta,
                                       List<Map<String, Object>> knowledgeEvidence,
                                       List<Map<String, Object>> workflowHints) {
        String template = promptRegistryService.resolvePromptValue("planner.master_v1", PromptTemplates.MASTER_PLANNING_PROMPT);
        StringBuilder prompt = new StringBuilder(template.formatted(planId, sessionId, effectiveGoal));
        if (planningMeta != null && !planningMeta.isEmpty()) {
            prompt.append("\n\nreconstructed_intent_context (must be used for blueprint generation):\n")
                    .append(planningMeta);
        }
        if (knowledgeEvidence != null && !knowledgeEvidence.isEmpty()) {
            prompt.append("\n\nknowledge_evidence_blocks (RAG evidence, high priority for blueprint grounding):\n")
                    .append(knowledgeEvidence);
        }
        if (workflowHints != null && !workflowHints.isEmpty()) {
            prompt.append("\n\nworkflow_hints_from_mcp (capabilities/resources/workflows):\n")
                    .append(workflowHints);
        }
        return prompt.toString();
    }

    private String cleanJsonFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            t = t.replaceAll("(?s)```\\s*$", "");
        }
        return t.trim();
    }

    private Map<String, Object> fallbackBlueprint(String planId,
                                                  String sessionId,
                                                  String reconstructedGoal,
                                                  InputReconstructionResult reconstructionResult,
                                                  List<Map<String, Object>> knowledgeEvidence,
                                                  List<Map<String, Object>> workflowHints) {
        String effectiveGoal = reconstructedGoal == null ? "" : reconstructedGoal.trim();
        Map<String, Object> blueprint = new LinkedHashMap<>();
        blueprint.put("planId", planId);
        blueprint.put("sessionId", sessionId);
        blueprint.put("userGoal", effectiveGoal);
        blueprint.put("reconstructedIntent", toReconstructionPayload(reconstructionResult));
        blueprint.put("knowledgeEvidence", normalizeSignalList(knowledgeEvidence, 12));
        blueprint.put("workflowHints", normalizeSignalList(workflowHints, 16));
        blueprint.put("createdAt", LocalDateTime.now().toString());

        List<Map<String, Object>> phases = new ArrayList<>();
        phases.add(new LinkedHashMap<>() {{
            put("phaseId", planId + ":phase-1");
            put("name", "RESEARCH");
            put("objective", "collect evidence");
            put("phaseOrder", 1);
        }});
        phases.add(new LinkedHashMap<>() {{
            put("phaseId", planId + ":phase-2");
            put("name", "PROMPT_SUMMARY");
            put("objective", "summarize findings");
            put("phaseOrder", 2);
        }});
        phases.add(new LinkedHashMap<>() {{
            put("phaseId", planId + ":phase-3");
            put("name", "INGEST");
            put("objective", "write to knowledge base");
            put("phaseOrder", 3);
        }});
        blueprint.put("phases", phases);

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(new LinkedHashMap<>() {{
            put("nodeId", "node-" + UUID.randomUUID());
            put("phaseId", planId + ":phase-1");
            put("name", "research-node");
            put("nodeType", "TOOL");
            put("riskLevel", "LOW");
            put("resourceHint", Map.of("intent", "search"));
        }});
        nodes.add(new LinkedHashMap<>() {{
            put("nodeId", "node-" + UUID.randomUUID());
            put("phaseId", planId + ":phase-2");
            put("name", "summarize-node");
            put("nodeType", "PROMPT");
            put("riskLevel", "LOW");
            put("resourceHint", Map.of("intent", "summarize"));
        }});
        nodes.add(new LinkedHashMap<>() {{
            put("nodeId", "node-" + UUID.randomUUID());
            put("phaseId", planId + ":phase-3");
            put("name", "ingest-node");
            put("nodeType", "TOOL");
            put("riskLevel", "LOW");
            put("resourceHint", Map.of("intent", "ingest_kb"));
        }});
        blueprint.put("nodes", nodes);

        String n1 = (String) nodes.get(0).get("nodeId");
        String n2 = (String) nodes.get(1).get("nodeId");
        String n3 = (String) nodes.get(2).get("nodeId");

        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(new LinkedHashMap<>() {{
            put("fromNodeId", n1);
            put("toNodeId", n2);
            put("conditionExpr", "");
        }});
        edges.add(new LinkedHashMap<>() {{
            put("fromNodeId", n2);
            put("toNodeId", n3);
            put("conditionExpr", "");
        }});
        blueprint.put("edges", edges);
        return blueprint;
    }

    private List<Map<String, Object>> normalizeSignalList(List<Map<String, Object>> rows, int limit) {
        if (rows == null || rows.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            out.add(new LinkedHashMap<>(row));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private Map<String, Object> toReconstructionPayload(InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null) {
            return Map.of();
        }
        return objectMapper.convertValue(reconstructionResult, new TypeReference<Map<String, Object>>() {});
    }
}
