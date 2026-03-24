package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.MasterPlanningService;
import org.yilena.luna.utils.LlmClientUtil;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Master Planner 实现：
 * - 使用 BigModel 一次性产出全局蓝图
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
    public Map<String, Object> generateBlueprint(String planId, String sessionId, String userGoal) {
        try {
            String prompt = buildPlanningPrompt(planId, sessionId, userGoal);

            LlmRequest req = LlmRequest.builder()
                    .modelType(ModelType.OPENAI_COMPATIBLE)
                    .modelName(geminiProperty.getBig().getModelName())
                    .messages(List.of(LlmMessage.user(prompt)))
                    .temperature(0.2)
                    .enablePromptInjectionCheck(false)
                    .build();

            LlmResponse resp = llmClientUtil.generate(req);
            String text = resp != null ? resp.getContent() : null;

            if (text == null || text.isBlank()) {
                log.warn("Master Planner 返回为空，使用回退蓝图");
                return fallbackBlueprint(planId, sessionId, userGoal);
            }

            String cleaned = cleanJsonFence(text);
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

            // 强制兜底关键字段
            map.putIfAbsent("planId", planId);
            map.putIfAbsent("sessionId", sessionId);
            map.putIfAbsent("userGoal", userGoal);
            map.putIfAbsent("createdAt", LocalDateTime.now().toString());

            return map;
        } catch (Exception e) {
            log.error("Master Planner 生成蓝图失败，使用回退蓝图", e);
            return fallbackBlueprint(planId, sessionId, userGoal);
        }
    }

    private String buildPlanningPrompt(String planId, String sessionId, String userGoal) {
        return """
你是 OpenClaw Master Planner。
你的任务是根据用户目标，一次性输出可执行的计划蓝图 JSON。

硬性要求：
1) 只输出一个合法 JSON 对象，不要 markdown，不要解释。
2) 你必须决定阶段数量（可为1..N），并给出每个阶段 objective。
3) nodes 必须归属到 phases，edges 必须引用存在的 nodeId。
4) nodeType 仅可使用：ANALYZE, TOOL, SKILL, VALIDATE, SUMMARIZE, REPORT, CODE
5) riskLevel 仅可使用：LOW, MEDIUM, HIGH
6) 必须包含字段：planId, sessionId, userGoal, createdAt, phases, nodes, edges
7) 每个 phase 必须有：phaseId, name, objective, phaseOrder
8) 每个 node 必须有：nodeId, phaseId, name, nodeType, riskLevel

输入：
planId=%s
sessionId=%s
userGoal=%s

输出结构示例（仅结构参考）：
{
  "planId": "...",
  "sessionId": "...",
  "userGoal": "...",
  "createdAt": "...",
  "phases": [
    {"phaseId":"...","name":"...","objective":"...","phaseOrder":1}
  ],
  "nodes": [
    {"nodeId":"...","phaseId":"...","name":"...","nodeType":"TOOL","riskLevel":"LOW","resourceHint":{"intent":"search"}}
  ],
  "edges": [
    {"fromNodeId":"...","toNodeId":"...","conditionExpr":""}
  ]
}
""".formatted(planId, sessionId, userGoal);
    }

    private String cleanJsonFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
            t = t.replaceAll("(?s)```\\s*$", "");
        }
        return t.trim();
    }

    private Map<String, Object> fallbackBlueprint(String planId, String sessionId, String userGoal) {
        Map<String, Object> blueprint = new LinkedHashMap<>();
        blueprint.put("planId", planId);
        blueprint.put("sessionId", sessionId);
        blueprint.put("userGoal", userGoal);
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
            put("name", "SUMMARIZE");
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
            put("nodeType", "SUMMARIZE");
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
}
