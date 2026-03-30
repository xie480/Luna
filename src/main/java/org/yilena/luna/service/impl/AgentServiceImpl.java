package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.adapter.LlmAdapter;
import org.yilena.luna.common.utils.JsonSchemaValidator;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.executor.SkillExecutor;
import org.yilena.luna.gate.ExecutionGate;
import org.yilena.luna.gate.ToolExecutionGateway;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.utils.AuthContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * AgentServiceImpl ??
 */
public class AgentServiceImpl implements AgentService {

    private final ToolRouter toolRouter; // 声明成员字段
    private final LlmAdapter llmAdapter; // 声明成员字段
    private final ExecutionGate executionGate; // 声明成员字段
    private final ToolExecutionGateway toolExecutionGateway; // 声明成员字段
    private final SkillExecutor skillExecutor; // 声明成员字段
    private final McpService mcpService; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段
    private final SessionService sessionService; // 声明成员字段

    @Override // 声明注解
    public String processToolCalling(String sessionId, String input) { // 定义方法签名
        log.info("processToolCalling, sessionId={}, input={}", sessionId, input); // 执行赋值操作

        List<Resource> candidates = toolRouter.findCandidates(input); // 执行赋值操作
        if (candidates.isEmpty()) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块

        List<String> history = loadRecentHistory(sessionId); // 执行赋值操作
        String decisionJson = llmAdapter.generate(buildDecisionPrompt(input, history, candidates)); // 执行赋值操作
        String targetName = parseToolName(decisionJson); // 执行赋值操作
        if (targetName == null || "none".equalsIgnoreCase(targetName) || "null".equalsIgnoreCase(targetName)) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块

        Resource target = candidates.stream() // 执行赋值操作
                .filter(r -> targetName.equals(r.getName())) // 执行当前逻辑
                .findFirst() // 执行当前逻辑
                .orElse(null); // 执行语句逻辑
        if (target == null) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块

        String argsJson = llmAdapter.generate(buildArgsPrompt(input, history, target)); // 执行赋值操作
        if (!JsonSchemaValidator.validate(target.getInputSchema(), argsJson)) { // 进行条件判断
            argsJson = llmAdapter.generate(String.format(PromptTemplates.TOOL_ARGS_REPAIR_PROMPT, target.getInputSchema(), argsJson)); // 执行赋值操作
        } // 结束当前代码块

        executionGate.check(target); // 执行语句逻辑
        if (ResourceType.WORKFLOW.equals(target.getType()) || ResourceType.SKILL.equals(target.getType())) { // 进行条件判断
            return skillExecutor.execute(target, argsJson); // 返回处理结果
        } // 结束当前代码块
        if (ResourceType.PROMPT.equals(target.getType())) { // 进行条件判断
            return toJson(Map.of("status", "success", "data", // 返回处理结果
                    mcpService.getPrompt(target.getServerCode(), target.getName(), argsJson))); // 执行语句逻辑
        } // 结束当前代码块
        if (ResourceType.RESOURCE.equals(target.getType())) { // 进行条件判断
            String uri = target.getResourceUri() == null ? target.getName() : target.getResourceUri(); // 执行赋值操作
            return toJson(Map.of("status", "success", "data", // 返回处理结果
                    mcpService.readResource(target.getServerCode(), uri))); // 执行语句逻辑
        } // 结束当前代码块

        String stableSessionId = resolveStableSessionId(sessionId); // 执行赋值操作
        ExecutionResult result = toolExecutionGateway.executeTool(stableSessionId, target, argsJson); // 执行赋值操作
        if (result.getRawResult() != null) { // 进行条件判断
            return result.getRawResult(); // 返回处理结果
        } // 结束当前代码块
        return toJson(Map.of( // 返回处理结果
                "status", result.getStatus(), // 执行当前逻辑
                "message", result.getMessage(), // 执行当前逻辑
                "data", result.getData() // 执行当前逻辑
        )); // 执行语句逻辑
    } // 结束当前代码块

    private String resolveStableSessionId(String sessionId) { // 定义方法签名
        String jwtJti = AuthContextHolder.getSessionId(); // 执行赋值操作
        if (jwtJti != null && !jwtJti.isBlank()) { // 进行条件判断
            return jwtJti; // 返回处理结果
        } // 结束当前代码块
        if (sessionId == null || sessionId.isBlank()) { // 进行条件判断
            return "agent-default"; // 返回处理结果
        } // 结束当前代码块
        return sessionId; // 返回处理结果
    } // 结束当前代码块

    private String buildDecisionPrompt(String input, List<String> history, List<Resource> tools) { // 定义方法签名
        String toolDesc = tools.stream() // 执行赋值操作
                .map(t -> "- " + t.getName() + ": " + t.getDescription()) // 执行当前逻辑
                .collect(Collectors.joining("\n")); // 执行语句逻辑
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history); // 执行赋值操作
        return String.format(PromptTemplates.TOOL_DECISION_PROMPT, input, historyText, toolDesc); // 返回处理结果
    } // 结束当前代码块

    private String buildArgsPrompt(String input, List<String> history, Resource resource) { // 定义方法签名
        String historyText = (history == null || history.isEmpty()) ? "(empty)" : String.join("\n", history); // 执行赋值操作
        if (ResourceType.WORKFLOW.equals(resource.getType()) || ResourceType.SKILL.equals(resource.getType())) { // 进行条件判断
            return String.format( // 返回处理结果
                    PromptTemplates.SKILL_ARGS_PROMPT, // 执行当前逻辑
                    input, // 执行当前逻辑
                    historyText, // 执行当前逻辑
                    resource.getName(), // 执行当前逻辑
                    resource.getDescription(), // 执行当前逻辑
                    resource.getInputSchema(), // 执行当前逻辑
                    buildWorkflowHint(resource) // 执行当前逻辑
            ); // 执行语句逻辑
        } // 结束当前代码块
        return String.format( // 返回处理结果
                PromptTemplates.TOOL_ARGS_PROMPT, // 执行当前逻辑
                input, // 执行当前逻辑
                historyText, // 执行当前逻辑
                resource.getName(), // 执行当前逻辑
                resource.getDescription(), // 执行当前逻辑
                resource.getInputSchema() // 执行当前逻辑
        ); // 执行语句逻辑
    } // 结束当前代码块

    private String buildWorkflowHint(Resource resource) { // 定义方法签名
        String capabilities = resource.getRequiredCapabilities() == null ? "[]" : resource.getRequiredCapabilities().toString(); // 执行赋值操作
        String slots = resource.getToolSlots() == null ? "[]" : resource.getToolSlots().stream() // 执行赋值操作
                .map(s -> "{slot=" + s.getSlot() + ", capability=" + s.getCapability() + ", required=" + s.getRequired() + "}") // 执行赋值操作
                .collect(Collectors.joining(", ", "[", "]")); // 执行语句逻辑
        String thoughtChain = resource.getThoughtChain() == null ? "[]" : resource.getThoughtChain().toString(); // 执行赋值操作
        return "requiredCapabilities=" + capabilities + ";toolSlots=" + slots + ";thoughtChain=" + thoughtChain; // 返回处理结果
    } // 结束当前代码块

    private List<String> loadRecentHistory(String sessionId) { // 定义方法签名
        if (sessionId == null || sessionId.isBlank()) { // 进行条件判断
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            List<ChatMessage> recent = sessionService.getRecentMessages(sessionId, false); // 执行赋值操作
            if (recent == null || recent.isEmpty()) { // 进行条件判断
                return Collections.emptyList(); // 返回处理结果
            } // 结束当前代码块
            int from = Math.max(0, recent.size() - 20); // 执行赋值操作
            return recent.subList(from, recent.size()).stream() // 返回处理结果
                    .map(m -> m.getRole().name() + ": " + m.getContent()) // 执行当前逻辑
                    .toList(); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.warn("load history failed: {}", e.getMessage()); // 执行语句逻辑
            return Collections.emptyList(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String parseToolName(String json) { // 定义方法签名
        if (json == null) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            String clean = json.trim().replace("```json", "").replace("```", ""); // 执行赋值操作
            JsonNode node = objectMapper.readTree(clean); // 执行赋值操作
            if (node.has("tool_name")) { // 进行条件判断
                return node.get("tool_name").asText(); // 返回处理结果
            } // 结束当前代码块
        } catch (Exception e) { // 开始新的代码块
            log.warn("parse tool decision failed: {}", json); // 执行语句逻辑
        } // 结束当前代码块
        return null; // 返回处理结果
    } // 结束当前代码块

    private String toJson(Object obj) { // 定义方法签名
        try { // 尝试执行核心逻辑
            return objectMapper.writeValueAsString(obj); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return "{\"status\":\"error\",\"message\":\"serialization failed\"}"; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
