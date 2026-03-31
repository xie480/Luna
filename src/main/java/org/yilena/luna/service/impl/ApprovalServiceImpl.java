package org.yilena.luna.service.impl;

import cn.hutool.core.lang.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.ModelHintConstant;
import org.yilena.luna.entity.ApprovalTask;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.prompt.PromptAssembler;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.ApprovalService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.sse.SseSessionManager;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.time.LocalTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 審批服務實現類
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final SseSessionManager sseSessionManager;
    private final LunaStatusPublisher statusPublisher;

    private final PromptAssembler promptAssembler;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final SessionService sessionService;
    private final EventIngressService eventIngressService;

    private static final String REDIS_PREFIX = "luna:approval:";
    private static final long EXPIRE_MINUTES = 10;

    @Override
    public void createTaskAndInterrupt(String sessionId, Resource resource, String argsJson) {
        String taskId = UUID.fastUUID().toString();

        ToolCallingContext callingContext = ToolCallingContextHolder.get();

        ApprovalTask task = ApprovalTask.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .skillName(resource.getName())
                .serverCode(resource.getServerCode())
                .toolName(resource.getName())
                .beanName(resource.getBeanName())
                .methodName(resource.getMethodName())
                .argsJson(argsJson)
                .createTime(System.currentTimeMillis())
                // chat续跑上下文（可能为空，需兼容非 chat 场景）
                .chatSessionKey(callingContext != null ? callingContext.getChatSessionKey() : null)
                .userInput(callingContext != null ? callingContext.getUserInput() : null)
                .memorySnippets(callingContext != null ? callingContext.getMemorySnippets() : null)
                .knowledgeSnippets(callingContext != null ? callingContext.getKnowledgeSnippets() : null)
                .preferenceSnippets(callingContext != null ? callingContext.getPreferenceSnippets() : null)
                .longTermMemorySnippets(callingContext != null ? callingContext.getLongTermMemorySnippets() : null)
                .build();

        // 1. 存入 Redis
        String key = REDIS_PREFIX + taskId;
        redisTemplate.opsForValue().set(key, task, EXPIRE_MINUTES, TimeUnit.MINUTES);

        log.info("已創建審批任務: {}, 等待用戶授權...", taskId);

        // 2. 推送 SSE 事件通知前端
        // 前端監聽 "APPROVAL_REQUEST" 事件
        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "APPROVAL_REQUEST", task);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "PENDING_APPROVAL", "操作需要你的确认...");

        // 3. 拋出異常中斷當前執行流
        throw new NeedApprovalException(task);
    }

    @Override
    public String processApproval(String taskId, boolean approved) {
        String key = REDIS_PREFIX + taskId;
        ApprovalTask task = (ApprovalTask) redisTemplate.opsForValue().get(key);

        if (task == null) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return errorJson("審批任務已過期或不存在");
        }

        // 無論同意還是拒絕，都刪除 Redis 中的任務
        redisTemplate.delete(key);

        String resultJson;
        if (task.getSessionId() != null && !task.getSessionId().isBlank()) {
            eventIngressService.ingestApproval(task.getSessionId(), Map.of("taskId", taskId, "approved", approved));
        }
        if (approved) {
            log.info("用戶同意了任務: {}, 開始執行工具...", taskId);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", "Luna 正在处理审批后的操作...");

            String toolResult = executeApprovedTool(task);
            resultJson = continueChatAfterToolDecision(task, toolResult, true);
        } else {
            log.info("用戶拒絕了任務: {}, 將跳過工具執行並繼續後續對話流程", taskId);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", "Luna 收到了你的选择，正在继续思考...");

            resultJson = continueChatAfterToolDecision(task, null, false);
        }

        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "APPROVAL_RESULT", Map.of(
                "taskId", taskId,
                "approved", approved,
                "result", safeToJsonNode(resultJson) != null ? safeToJsonNode(resultJson) : resultJson
        ));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");

        return resultJson;
    }

    private String executeApprovedTool(ApprovalTask task) {
        String toolName = task.getToolName();
        if (toolName == null || toolName.isBlank()) {
            toolName = task.getSkillName();
        }
        if (toolName == null || toolName.isBlank()) {
            return errorJson("approval task missing tool name");
        }

        try {
            McpToolCallResult result = mcpService.callTool(task.getServerCode(), toolName, task.getArgsJson());
            if (result == null) {
                return errorJson("tool execution returned null");
            }
            if (result.getRawResult() != null && !result.getRawResult().isBlank()) {
                return result.getRawResult();
            }
            return objectMapper.writeValueAsString(
                    result.getData() == null ? Map.of("status", "success") : result.getData()
            );
        } catch (Exception e) {
            log.error("approval execute tool failed, toolName={}", toolName, e);
            return errorJson("approval execute tool failed: " + e.getMessage());
        }
    }

    /**
     * 审批后统一续跑 chat 的 “tool calling 之后”逻辑：
     * - approved=true  -> 带 toolContext
     * - approved=false -> 跳过 toolContext
     */
    private String continueChatAfterToolDecision(ApprovalTask task, String toolContext, boolean approved) {
        // 若无 chat 上下文，回退旧逻辑（例如异常自修复链路触发审批）
        if (!canContinueChat(task)) {
            if (!approved) {
                return errorJson("User denied the operation.");
            }
            return wrapToolResultAsReply(toolContext);
        }

        try {
            String prompt = promptAssembler.assembleFinalPrompt(
                    task.getMemorySnippets() != null ? task.getMemorySnippets() : Collections.emptyList(),
                    task.getKnowledgeSnippets() != null ? task.getKnowledgeSnippets() : Collections.emptyList(),
                    task.getPreferenceSnippets() != null ? task.getPreferenceSnippets() : Collections.emptyList(),
                    task.getLongTermMemorySnippets() != null ? task.getLongTermMemorySnippets() : Collections.emptyList(),
                    approved ? toolContext : null,
                    task.getUserInput()
            );

            SendToLuna result = getSendToLuna(prompt, task.getUserInput());

            // 写回会话，保证历史完整
            sessionService.appendMessage(
                    task.getChatSessionKey(),
                    new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now())
            );

            // 返回对外 JSON（已去 thought）
            return result.valid();
        } catch (Exception e) {
            log.error("审批后续跑 chat 失败: {}", e.getMessage(), e);
            return wrapToolResultAsReply(toolContext);
        }
    }

    private boolean canContinueChat(ApprovalTask task) {
        return task != null
                && task.getChatSessionKey() != null
                && !task.getChatSessionKey().isBlank()
                && task.getUserInput() != null
                && !task.getUserInput().isBlank();
    }

    private SendToLuna getSendToLuna(String prompt, String originalUserInput) {
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(geminiProperty.getBig().getModelName())
                .messages(java.util.List.of(LlmMessage.user(prompt)))
                .enablePromptInjectionCheck(true)
                .build();

        LlmResponse response = llmClientUtil.generate(request);
        String valid = response != null ? response.getContent() : null;

        if (valid == null) {
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        JsonNode node = tryParseJsonNode(valid);
        if (!isValidReplyNode(node)) {
            try {
                String repairSeed = (originalUserInput != null && !originalUserInput.isBlank()) ? originalUserInput : valid;
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(repairSeed);

                LlmRequest repairReq = LlmRequest.builder()
                        .modelType(ModelType.OPENAI_COMPATIBLE)
                        .modelName(geminiProperty.getBig().getModelName())
                        .messages(java.util.List.of(LlmMessage.user(repairPrompt)))
                        .enablePromptInjectionCheck(false)
                        .build();

                LlmResponse repairRes = llmClientUtil.generate(repairReq);
                String repairedText = repairRes != null ? repairRes.getContent() : null;

                if (repairedText != null) {
                    JsonNode repairedNode = tryParseJsonNode(repairedText);
                    if (isValidReplyNode(repairedNode)) {
                        String raw = repairedNode.toString();
                        String cleanJson = removeThoughtFromJson(raw);
                        return new SendToLuna(raw, cleanJson, repairedNode.get(ModelHintConstant.REPLY).asText());
                    }
                }
            } catch (Exception e) {
                log.warn("审批后修复流程失败: {}", e.getMessage());
            }

            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        String replyText = node.get(ModelHintConstant.REPLY).asText();
        String raw = node.toString();
        String cleanValid = removeThoughtFromJson(raw);
        return new SendToLuna(raw, cleanValid, replyText);
    }

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
                    .replaceAll("(?s)```\\s*$", "")
                    .trim();
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidReplyNode(JsonNode node) {
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) && node.get(ModelHintConstant.REPLY).isTextual();
    }

    private String createFallbackJson() {
        return "{\"thought\":\"系统降级，无法进行思考。\",\"emotion\":\"Solemn\",\"reply\":\"生成回复失败，请稍后重试。\"}";
    }

    private String extractReplyFromJsonSafe(String json) {
        JsonNode node = tryParseJsonNode(json);
        if (node != null && node.hasNonNull(ModelHintConstant.REPLY)) {
            return node.get(ModelHintConstant.REPLY).asText();
        }
        return "";
    }

    private String removeThoughtFromJson(String json) {
        try {
            JsonNode node = tryParseJsonNode(json);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.remove("thought");
                return objectNode.toString();
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    private String wrapToolResultAsReply(String toolResult) {
        try {
            String replyText = "操作已完成。";
            JsonNode toolNode = safeToJsonNode(toolResult);
            if (toolNode != null) {
                if (toolNode.has("message")) {
                    replyText = toolNode.get("message").asText(replyText);
                } else if (toolNode.has("data")) {
                    replyText = "操作已完成，结果如下：" + toolNode.get("data").toString();
                } else {
                    replyText = "操作已完成，结果如下：" + toolNode.toString();
                }
            } else if (toolResult != null && !toolResult.isBlank()) {
                replyText = "操作已完成，结果如下：" + toolResult;
            }

            Map<String, Object> out = new java.util.HashMap<>();
            out.put("emotion", "Smile");
            out.put("reply", replyText);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"emotion\":\"Smile\",\"reply\":\"操作已完成。\"}";
        }
    }

    private JsonNode safeToJsonNode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String errorJson(String msg) {
        try {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("status", "error");
            map.put("message", msg);
            map.put("emotion", "Solemn");
            map.put("reply", "这次操作未能执行。原因：" + msg);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"" + msg + "\",\"emotion\":\"Solemn\",\"reply\":\"这次操作未能执行。\"}";
        }
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
