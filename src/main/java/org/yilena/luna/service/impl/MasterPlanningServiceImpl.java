package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.LlmConstant;
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
/**
 * 主规划服务实现，负责将用户目标、重构意图、知识证据和能力提示整合为可执行的计划蓝图。
 */
public class MasterPlanningServiceImpl implements MasterPlanningService {

    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final ObjectMapper objectMapper;
    private final PromptRegistryService promptRegistryService;

    // ... existing code ...

    /**
     * 生成计划蓝图，将用户目标、重构意图、知识证据和能力提示整合为结构化的执行计划。
     * <p>
     * 该方法的核心流程包括：
     * 1. 对输入的证据和工作流提示进行裁剪与标准化，控制上下文长度
     * 2. 验证重构后的任务目标有效性
     * 3. 组装包含意图、证据和能力的规划提示词
     * 4. 调用主规划模型（LLM）生成蓝图JSON结构
     * 5. 清洗并解析模型输出，补充元数据字段
     * 6. 异常时降级使用固定的三阶段骨架蓝图
     * <p>
     * 生成的蓝图包含阶段划分、节点定义、依赖关系等完整信息，是后续阶段执行的依据。
     *
     * @param planId              计划ID，用于标识本次计划实例
     * @param sessionId           会话ID，关联用户会话上下文
     * @param reconstructedGoal   重构后的任务目标，经过意图分析和规范化处理，不能为空或空白
     * @param reconstructionResult 输入重构结果，包含原始意图的详细信息，可为空
     * @param knowledgeEvidence   RAG召回的知识证据列表，提供领域知识和历史参考，可为空
     * @param workflowHints       工作流能力提示列表，包含可用的工具、提示词、资源、工作流等，可为空
     * @return Map&lt;String, Object&gt; 蓝图数据结构，包含：
     *         - planId: 计划ID
     *         - sessionId: 会话ID
     *         - userGoal: 用户目标
     *         - createdAt: 创建时间戳
     *         - reconstructedIntent: 重构意图对象
     *         - knowledgeEvidence: 标准化后的知识证据列表（最多12个）
     *         - workflowHints: 标准化后的能力提示列表（最多16个）
     *         - phases: 阶段列表，定义计划的各个执行阶段
     *         - nodes: 节点列表，定义具体的执行节点
     *         - edges: 边列表，定义节点间的依赖关系
     */
    @Override
    public Map<String, Object> generateBlueprint(String planId,
                                                 String sessionId,
                                                 String reconstructedGoal,
                                                 InputReconstructionResult reconstructionResult,
                                                 List<Map<String, Object>> knowledgeEvidence,
                                                 List<Map<String, Object>> workflowHints) {
        /**
         * 先对证据和工作流提示做裁剪与标准化，避免超长上下文影响主规划模型输出稳定性。
         */
        List<Map<String, Object>> normalizedKnowledgeEvidence = normalizeSignalList(knowledgeEvidence, 12);
        List<Map<String, Object>> normalizedWorkflowHints = normalizeSignalList(workflowHints, 16);
        try {
            /**
             * 先校验重构后的目标是否可用，这是后续生成蓝图的最小业务前提。
             */
            String effectiveGoal = reconstructedGoal == null ? "" : reconstructedGoal.trim();
            if (effectiveGoal.isBlank()) {
                throw new IllegalArgumentException("reconstructed goal is blank");
            }

            /**
             * 组装主规划 Prompt，并将重构意图、RAG 证据和工作流能力提示注入模型输入。
             */
            Map<String, Object> reconstructedIntent = toReconstructionPayload(reconstructionResult);
            String prompt = buildPlanningPrompt(
                    planId,
                    sessionId,
                    effectiveGoal,
                    reconstructedIntent,
                    normalizedKnowledgeEvidence,
                    normalizedWorkflowHints
            );

            /**
             * 调用主规划模型生成蓝图草案，模型返回的是整个计划图的 JSON 结构。
             */
            LlmRequest req = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(resolvePlanningModelName())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(LlmConstant.TASK_TEMPERATURE)
                    .enablePromptInjectionCheck(false)
                    .build();

            LlmResponse resp = llmClientUtil.generate(req);
            String text = resp != null ? resp.getContent() : null;
            if (text == null || text.isBlank()) {
                log.warn("Master planner returned empty content, use fallback blueprint");
                return fallbackBlueprint(planId, sessionId, effectiveGoal, reconstructionResult, normalizedKnowledgeEvidence, normalizedWorkflowHints);
            }

            /**
             * 清洗模型输出中的 Markdown 包装并反序列化为蓝图结构，同时补齐必要元字段。
             */
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
            /**
             * 当模型输出异常或解析失败时，退回固定骨架蓝图，保证规划链路可继续演进。
             */
            log.error("Master planner blueprint generation failed, use fallback", e);
            return fallbackBlueprint(planId, sessionId, reconstructedGoal, reconstructionResult, normalizedKnowledgeEvidence, normalizedWorkflowHints);
        }
    }

    // ... existing code ...


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
        /**
         * 以治理中心模板为基础拼接补充上下文，让模型在统一框架下吸收意图、证据和能力线索。
         */
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
        /**
         * 回退蓝图采用固定三阶段结构，至少保证“检索-总结-写入”主流程能够被执行。
         */
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
        /**
         * 先构造阶段骨架，明确每个阶段的职责和执行顺序。
         */
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
        /**
         * 再为每个阶段补一组最低可运行节点，使回退蓝图仍能形成完整执行图。
         */
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
        /**
         * 最后串联阶段节点连线，确保执行器能按顺序推进整个回退计划。
         */
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
