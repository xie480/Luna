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

    private final LlmClientUtil llmClientUtil; // 声明成员字段
    private final GeminiProperty geminiProperty; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段

    @Override // 声明注解
    public Map<String, Object> generateBlueprint(String planId, String sessionId, String userGoal) { // 定义方法签名
        try { // 尝试执行核心逻辑
            String prompt = buildPlanningPrompt(planId, sessionId, userGoal); // 执行赋值操作

            String planningModel = resolvePlanningModelName(); // 执行赋值操作

            LlmRequest req = LlmRequest.builder() // 执行赋值操作
                    .modelType(ModelType.OPENAI_COMPATIBLE) // 执行当前逻辑
                    .modelName(planningModel) // 执行当前逻辑
                    .messages(List.of(LlmMessage.user(prompt))) // 执行当前逻辑
                    .temperature(0.2) // 执行当前逻辑
                    .enablePromptInjectionCheck(false) // 执行当前逻辑
                    .build(); // 执行语句逻辑

            LlmResponse resp = llmClientUtil.generate(req); // 执行赋值操作
            String text = resp != null ? resp.getContent() : null; // 执行赋值操作

            if (text == null || text.isBlank()) { // 进行条件判断
                log.warn("Master Planner 返回为空，使用回退蓝图"); // 执行语句逻辑
                return fallbackBlueprint(planId, sessionId, userGoal); // 返回处理结果
            } // 结束当前代码块

            String cleaned = cleanJsonFence(text); // 执行赋值操作
            Map<String, Object> map = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {}); // 执行赋值操作

            map.putIfAbsent("planId", planId); // 执行语句逻辑
            map.putIfAbsent("sessionId", sessionId); // 执行语句逻辑
            map.putIfAbsent("userGoal", userGoal); // 执行语句逻辑
            map.putIfAbsent("createdAt", LocalDateTime.now().toString()); // 执行语句逻辑

            return map; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("Master Planner 生成蓝图失败，使用回退蓝图", e); // 执行语句逻辑
            return fallbackBlueprint(planId, sessionId, userGoal); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String resolvePlanningModelName() { // 定义方法签名
        if (geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null && !geminiProperty.getCode().getModelName().isBlank()) { // 进行条件判断
            return geminiProperty.getCode().getModelName(); // 返回处理结果
        } // 结束当前代码块
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null && !geminiProperty.getBig().getModelName().isBlank()) { // 进行条件判断
            return geminiProperty.getBig().getModelName(); // 返回处理结果
        } // 结束当前代码块
        throw new IllegalStateException("未配置可用的规划模型（gemini.code 或 gemini.big）"); // 抛出异常信息
    } // 结束当前代码块

    private String buildPlanningPrompt(String planId, String sessionId, String userGoal) { // 定义方法签名
        return PromptTemplates.MASTER_PLANNING_PROMPT.formatted(planId, sessionId, userGoal); // 返回处理结果
    } // 结束当前代码块

    private String cleanJsonFence(String text) { // 定义方法签名
        String t = text.trim(); // 执行赋值操作
        if (t.startsWith("```")) { // 进行条件判断
            t = t.replaceAll("(?s)^```[a-zA-Z]*\\s*", ""); // 执行赋值操作
            t = t.replaceAll("(?s)```\\s*$", ""); // 执行赋值操作
        } // 结束当前代码块
        return t.trim(); // 返回处理结果
    } // 结束当前代码块

    private Map<String, Object> fallbackBlueprint(String planId, String sessionId, String userGoal) { // 定义方法签名
        Map<String, Object> blueprint = new LinkedHashMap<>(); // 执行赋值操作
        blueprint.put("planId", planId); // 执行语句逻辑
        blueprint.put("sessionId", sessionId); // 执行语句逻辑
        blueprint.put("userGoal", userGoal); // 执行语句逻辑
        blueprint.put("createdAt", LocalDateTime.now().toString()); // 执行语句逻辑

        List<Map<String, Object>> phases = new ArrayList<>(); // 执行赋值操作
        phases.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("phaseId", planId + ":phase-1"); // 执行语句逻辑
            put("name", "RESEARCH"); // 执行语句逻辑
            put("objective", "检索信息"); // 执行语句逻辑
            put("phaseOrder", 1); // 执行语句逻辑
        }}); // 执行语句逻辑
        phases.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("phaseId", planId + ":phase-2"); // 执行语句逻辑
            put("name", "SUMMARIZE"); // 执行语句逻辑
            put("objective", "整理结果"); // 执行语句逻辑
            put("phaseOrder", 2); // 执行语句逻辑
        }}); // 执行语句逻辑
        phases.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("phaseId", planId + ":phase-3"); // 执行语句逻辑
            put("name", "INGEST"); // 执行语句逻辑
            put("objective", "写入知识库"); // 执行语句逻辑
            put("phaseOrder", 3); // 执行语句逻辑
        }}); // 执行语句逻辑
        blueprint.put("phases", phases); // 执行语句逻辑

        List<Map<String, Object>> nodes = new ArrayList<>(); // 执行赋值操作
        nodes.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("nodeId", "node-" + UUID.randomUUID()); // 执行语句逻辑
            put("phaseId", planId + ":phase-1"); // 执行语句逻辑
            put("name", "research-node"); // 执行语句逻辑
            put("nodeType", "TOOL"); // 执行语句逻辑
            put("riskLevel", "LOW"); // 执行语句逻辑
            put("resourceHint", Map.of("intent", "search")); // 执行语句逻辑
        }}); // 执行语句逻辑
        nodes.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("nodeId", "node-" + UUID.randomUUID()); // 执行语句逻辑
            put("phaseId", planId + ":phase-2"); // 执行语句逻辑
            put("name", "summarize-node"); // 执行语句逻辑
            put("nodeType", "SUMMARIZE"); // 执行语句逻辑
            put("riskLevel", "LOW"); // 执行语句逻辑
            put("resourceHint", Map.of("intent", "summarize")); // 执行语句逻辑
        }}); // 执行语句逻辑
        nodes.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("nodeId", "node-" + UUID.randomUUID()); // 执行语句逻辑
            put("phaseId", planId + ":phase-3"); // 执行语句逻辑
            put("name", "ingest-node"); // 执行语句逻辑
            put("nodeType", "TOOL"); // 执行语句逻辑
            put("riskLevel", "LOW"); // 执行语句逻辑
            put("resourceHint", Map.of("intent", "ingest_kb")); // 执行语句逻辑
        }}); // 执行语句逻辑
        blueprint.put("nodes", nodes); // 执行语句逻辑

        String n1 = (String) nodes.get(0).get("nodeId"); // 执行赋值操作
        String n2 = (String) nodes.get(1).get("nodeId"); // 执行赋值操作
        String n3 = (String) nodes.get(2).get("nodeId"); // 执行赋值操作

        List<Map<String, Object>> edges = new ArrayList<>(); // 执行赋值操作
        edges.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("fromNodeId", n1); // 执行语句逻辑
            put("toNodeId", n2); // 执行语句逻辑
            put("conditionExpr", ""); // 执行语句逻辑
        }}); // 执行语句逻辑
        edges.add(new LinkedHashMap<>() {{ // 开始新的代码块
            put("fromNodeId", n2); // 执行语句逻辑
            put("toNodeId", n3); // 执行语句逻辑
            put("conditionExpr", ""); // 执行语句逻辑
        }}); // 执行语句逻辑
        blueprint.put("edges", edges); // 执行语句逻辑

        return blueprint; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
