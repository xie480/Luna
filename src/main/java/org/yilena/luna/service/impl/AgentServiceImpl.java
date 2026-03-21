package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.common.utils.JsonSchemaValidator;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.executor.ReflectionToolExecutor;
import org.yilena.luna.executor.SkillExecutor;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.Collections;
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
    private final SessionService sessionService;

    @Override
    public String processToolCalling(String sessionId, String input) {
        log.info("Agent 开始进行工具决策分析: {}, sessionId={}", input, sessionId);

        // 1. 獲取候選工具
        List<Resource> candidates = toolRouter.findCandidates(input);
        if (candidates.isEmpty()) {
            log.info("未检索到相关工具，跳过 Tool Calling");
            return null;
        }

        // 1.1 拉取近期历史对话，参与工具决策
        List<String> historySnippets = loadRecentHistory(sessionId);

        // 2. 決策階段 (調用 LLM 判斷是否需要工具)
        String decisionPrompt = buildDecisionPrompt(input, historySnippets, candidates);
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
            // 优先使用 JWT 的 jti 作为稳定会话ID；若不存在再回退入参
            String jwtJti = AuthContextHolder.getSessionId();
            String stableSessionId = (jwtJti != null && !jwtJti.isBlank())
                    ? jwtJti
                    : ((sessionId == null || sessionId.isBlank()) ? "agent-default" : sessionId);

            executionResult = toolExecutor.execute(stableSessionId, targetResource, argsJson);
        }

        log.info("Agent 工具执行完毕，结果: {}", executionResult);
        return executionResult;
    }

    // --- Prompt 構建輔助方法 ---

    private String buildDecisionPrompt(String input, List<String> historySnippets, List<Resource> tools) {
        String toolDesc = tools.stream()
                .map(t -> String.format("- %s: %s", t.getName(), t.getDescription()))
                .collect(Collectors.joining("\n"));

        String historyText;
        if (historySnippets == null || historySnippets.isEmpty()) {
            historyText = "（无可用历史对话）";
        } else {
            historyText = historySnippets.stream().collect(Collectors.joining("\n"));
        }

        return String.format(PromptTemplates.TOOL_DECISION_PROMPT, input, historyText, toolDesc);
    }

    private String buildArgsPrompt(String input, Resource tool) {
        return String.format(PromptTemplates.TOOL_ARGS_PROMPT, tool.getName(), input, tool.getDescription(), tool.getInputSchema());
    }

    private List<String> loadRecentHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<ChatMessage> recent = sessionService.getRecentMessages(sessionId, false);
            if (recent == null || recent.isEmpty()) {
                return Collections.emptyList();
            }
            // 只取最近 20 条，避免决策提示词过长
            int from = Math.max(0, recent.size() - 20);
            return recent.subList(from, recent.size()).stream()
                    .map(m -> m.getRole().name() + ": " + m.getContent())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("加载历史对话失败，sessionId={}, err={}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
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
