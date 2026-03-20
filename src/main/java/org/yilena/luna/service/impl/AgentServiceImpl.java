package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.common.utils.JsonSchemaValidator;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.executor.ReflectionToolExecutor;
import org.yilena.luna.executor.SkillExecutor;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 編排核心實現類
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final ToolRouter toolRouter;
    private final LlmAdapter llmAdapter; // 默認注入 RealLlmAdapter
    private final ExecutionGate executionGate;
    private final ReflectionToolExecutor toolExecutor;
    private final SkillExecutor skillExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public String processToolCalling(String input) {
        log.info("Agent 开始进行工具决策分析: {}", input);

        // 1. 獲取候選工具
        List<Resource> candidates = toolRouter.findCandidates(input);
        if (candidates.isEmpty()) {
            log.info("未检索到相关工具，跳过 Tool Calling");
            return null;
        }

        // 2. 決策階段 (調用 LLM 判斷是否需要工具)
        String decisionPrompt = buildDecisionPrompt(input, candidates);
        String decisionJson = llmAdapter.generate(decisionPrompt);
        log.info("Agent 决策结果: {}", decisionJson);

        String toolName = parseToolName(decisionJson);
        if (toolName == null || "null".equalsIgnoreCase(toolName) || "none".equalsIgnoreCase(toolName)) {
            log.info("Agent 判断：无需调用工具");
            return null;
        }

        Resource targetResource = candidates.stream()
                .filter(r -> r.getName().equals(toolName))
                .findFirst()
                .orElse(null);

        if (targetResource == null) {
            log.warn("决策出的工具 [{}] 不在候选列表中", toolName);
            return null;
        }

        // 3. 參數生成階段
        String argsPrompt = buildArgsPrompt(input, targetResource);
        String argsJson = llmAdapter.generate(argsPrompt);
        log.info("Agent 生成参数: {}", argsJson);

        // 4. JSON Schema 校驗與修復
        if (!JsonSchemaValidator.validate(targetResource.getInputSchema(), argsJson)) {
            log.warn("参数校验失败，尝试自动修复...");
            String repairPrompt = String.format(PromptTemplates.TOOL_ARGS_REPAIR_PROMPT, 
                    targetResource.getInputSchema(), argsJson);
            argsJson = llmAdapter.generate(repairPrompt);
        }

        // 5. 權限與審批網關
        try {
            executionGate.check(targetResource);
        } catch (Exception e) {
            return "【系统警告】工具执行被拦截: " + e.getMessage();
        }

        // 6. 執行工具或技能
        String executionResult;
        if (ResourceType.SKILL.equals(targetResource.getType())) {
            executionResult = skillExecutor.execute(targetResource, argsJson);
        } else {
            executionResult = toolExecutor.execute(targetResource, argsJson);
        }
        
        log.info("Agent 工具执行完毕，结果: {}", executionResult);
        return executionResult;
    }

    // --- Prompt 構建輔助方法 ---

    private String buildDecisionPrompt(String input, List<Resource> tools) {
        String toolDesc = tools.stream()
                .map(t -> String.format("- %s: %s", t.getName(), t.getDescription()))
                .collect(Collectors.joining("\n"));
        
        return String.format(PromptTemplates.TOOL_DECISION_PROMPT, input, toolDesc);
    }

    private String buildArgsPrompt(String input, Resource tool) {
        return String.format(PromptTemplates.TOOL_ARGS_PROMPT, tool.getName(), input, tool.getDescription(), tool.getInputSchema());
    }

    private String parseToolName(String json) {
        if (json == null) return null;
        try {
            // 簡單清洗
            String clean = json.trim().replace("```json", "").replace("```", "");
            JsonNode node = objectMapper.readTree(clean);
            if (node.has("tool_name")) {
                return node.get("tool_name").asText();
            }
        } catch (Exception e) {
            log.error("解析决策 JSON 失败: {}", json, e);
        }
        return null;
    }
}
