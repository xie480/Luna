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
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.MasterPlanningService;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Master Planner 实现：
 * - 使用 code 模型一次性产出全局蓝图
 * - 若模型输出不合法，回退到最小可执行蓝图
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterPlanningServiceImpl implements MasterPlanningService {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> generateBlueprint(String planId,
                                                 String sessionId,
                                                 String userGoal,
                                                 InputReconstructionResult reconstructionResult) {
        try {
            String effectiveGoal = resolveEffectiveGoal(userGoal, reconstructionResult);
            Map<String, Object> reconstructedIntent = toReconstructionPayload(reconstructionResult);
            String prompt = buildPlanningPrompt(planId, sessionId, effectiveGoal, reconstructedIntent);

            String planningModel = resolvePlanningModelName();

            LlmRequest req = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(planningModel)
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.2)
                    .enablePromptInjectionCheck(false)
                    .build();

            LlmResponse resp = llmClientUtil.generate(req);
            String text = resp != null ? resp.getContent() : null;

            if (text == null || text.isBlank()) {
                log.warn("Master Planner 返回为空，使用回退蓝图");
                return fallbackBlueprint(planId, sessionId, userGoal, reconstructionResult);
            }

            String cleaned = cleanJsonFence(text);
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

            map.putIfAbsent("planId", planId);
            map.putIfAbsent("sessionId", sessionId);
            map.putIfAbsent("userGoal", effectiveGoal);
            map.putIfAbsent("createdAt", LocalDateTime.now().toString());
            map.putIfAbsent("reconstructedIntent", reconstructedIntent);

            return map;
        } catch (Exception e) {
            log.error("Master Planner 生成蓝图失败，使用回退蓝图", e);
            return fallbackBlueprint(planId, sessionId, userGoal, reconstructionResult);
        }
    }

    private String resolvePlanningModelName() {
        if (geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null && !geminiProperty.getCode().getModelName().isBlank()) {
            return geminiProperty.getCode().getModelName();
        }
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null && !geminiProperty.getBig().getModelName().isBlank()) {
            return geminiProperty.getBig().getModelName();
        }
        throw new IllegalStateException("未配置可用的规划模型（gemini.code 或 gemini.big）");
    }

    private String buildPlanningPrompt(String planId,
                                       String sessionId,
                                       String effectiveGoal,
                                       Map<String, Object> planningMeta) {
        String base = PromptTemplates.MASTER_PLANNING_PROMPT.formatted(planId, sessionId, effectiveGoal);
        if (planningMeta == null || planningMeta.isEmpty()) {
            return base;
        }
        return base + "\n\nreconstructed_intent_context (must be used for blueprint generation):\n" + planningMeta;
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
                                                  String userGoal,
                                                  InputReconstructionResult reconstructionResult) {
        String effectiveGoal = resolveEffectiveGoal(userGoal, reconstructionResult);
        Map<String, Object> blueprint = new LinkedHashMap<>();
        blueprint.put("planId", planId);
        blueprint.put("sessionId", sessionId);
        blueprint.put("userGoal", effectiveGoal);
        blueprint.put("reconstructedIntent", toReconstructionPayload(reconstructionResult));
        blueprint.put("createdAt", LocalDateTime.now().toString());

        List<Map<String, Object>> phases = new ArrayList<>();
        phases.add(new LinkedHashMap<>() {{
            put("phaseId", planId + ":phase-1");
            put("name", "RESEARCH");
            put("objective", "检索信息");
            put("phaseOrder", 1);
        }});
        phases.add(new LinkedHashMap<>() {{
            put("phaseId", planId + ":phase-2");
            put("name", "PROMPT_SUMMARY");
            put("objective", "整理结果");
            put("phaseOrder", 2);
        }});
        phases.add(new LinkedHashMap<>() {{
            put("phaseId", planId + ":phase-3");
            put("name", "INGEST");
            put("objective", "写入知识库");
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

    private String resolveEffectiveGoal(String userGoal, InputReconstructionResult reconstructionResult) {
        if (reconstructionResult != null
                && reconstructionResult.getExplicitTaskGoal() != null
                && !reconstructionResult.getExplicitTaskGoal().isBlank()) {
            return reconstructionResult.getExplicitTaskGoal();
        }
        return userGoal == null ? "" : userGoal;
    }

    private Map<String, Object> toReconstructionPayload(InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null) {
            return Map.of();
        }
        return objectMapper.convertValue(reconstructionResult, new TypeReference<Map<String, Object>>() {});
    }
}
