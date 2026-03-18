package org.yilena.luna.service;

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
import org.yilena.luna.router.ToolRouter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 編排核心
 * 專注於 MCP Tool Calling 的決策、參數生成與執行閉環
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ToolRouter toolRouter;
    private final LlmAdapter llmAdapter; // 默認注入 RealLlmAdapter
    private final ExecutionGate executionGate;
    private final ReflectionToolExecutor toolExecutor;
    private final SkillExecutor skillExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 處理工具調用流程 (替換原有的 executeToolsIfNecessary)
     * @param input 用戶自然語言輸入
     * @return 工具執行的結果上下文，如果不需要調用工具則返回 null
     */
    public String processToolCalling(String input) {
        log.info("Agent 開始進行工具決策分析: {}", input);

        // 1. 獲取候選工具
        List<Resource> candidates = toolRouter.findCandidates(input);
        if (candidates.isEmpty()) {
            log.info("未檢索到相關工具，跳過 Tool Calling");
            return null;
        }

        // 2. 決策階段 (調用 LLM 判斷是否需要工具)
        String decisionPrompt = buildDecisionPrompt(input, candidates);
        String decisionJson = llmAdapter.generate(decisionPrompt);
        log.info("Agent 決策結果: {}", decisionJson);

        String toolName = parseToolName(decisionJson);
        if (toolName == null || "null".equalsIgnoreCase(toolName) || "none".equalsIgnoreCase(toolName)) {
            log.info("Agent 判斷：無需調用工具");
            return null;
        }

        Resource targetResource = candidates.stream()
                .filter(r -> r.getName().equals(toolName))
                .findFirst()
                .orElse(null);

        if (targetResource == null) {
            log.warn("決策出的工具 [{}] 不在候選列表中", toolName);
            return null;
        }

        // 3. 參數生成階段
        String argsPrompt = buildArgsPrompt(input, targetResource);
        String argsJson = llmAdapter.generate(argsPrompt);
        log.info("Agent 生成參數: {}", argsJson);

        // 4. JSON Schema 校驗與修復
        if (!JsonSchemaValidator.validate(targetResource.getInputSchema(), argsJson)) {
            log.warn("參數校驗失敗，嘗試自動修復...");
            String repairPrompt = String.format("參數不符合 Schema，請修復。\nSchema: %s\n無效参数: %s", 
                    targetResource.getInputSchema(), argsJson);
            argsJson = llmAdapter.generate(repairPrompt);
        }

        // 5. 權限與審批網關
        try {
            executionGate.check(targetResource);
        } catch (Exception e) {
            return "【系統警告】工具執行被攔截: " + e.getMessage();
        }

        // 6. 執行工具或技能
        String executionResult;
        if (ResourceType.SKILL.equals(targetResource.getType())) {
            executionResult = skillExecutor.execute(targetResource, argsJson);
        } else {
            executionResult = toolExecutor.execute(targetResource, argsJson);
        }
        
        log.info("Agent 工具執行完畢，結果: {}", executionResult);
        return executionResult;
    }

    // --- Prompt 構建輔助方法 ---

    private String buildDecisionPrompt(String input, List<Resource> tools) {
        String toolDesc = tools.stream()
                .map(t -> String.format("- %s: %s", t.getName(), t.getDescription()))
                .collect(Collectors.joining("\n"));
        
        return String.format("""
                你是一個智能決策助手。請根據用戶輸入和可用工具列表，判斷是否需要調用工具。
                
                用戶輸入: %s
                
                可用工具:
                %s
                
                請只返回一個 JSON 對象，格式如下：
                {"tool_name": "工具名稱"}
                如果不需要調用工具，請返回:
                {"tool_name": "none"}
                """, input, toolDesc);
    }

    private String buildArgsPrompt(String input, Resource tool) {
        return String.format("""
                請根據用戶輸入，為工具 "%s" 生成調用參數。
                
                用戶輸入: %s
                工具描述: %s
                參數 Schema: %s
                
                請只返回參數的 JSON 字符串，不要包含 Markdown 標記。
                """, tool.getName(), input, tool.getDescription(), tool.getInputSchema());
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
            log.error("解析決策 JSON 失敗: {}", json, e);
        }
        return null;
    }
}
