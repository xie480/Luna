package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.ModelHintConstant;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
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
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.ApprovalService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.sse.SseSessionManager;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.SnowflakeIdUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.time.LocalTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private static final String REDIS_PREFIX = "luna:approval:";
    private static final long EXPIRE_MINUTES = 10;
    private static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_FAILED = "FAILED";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final SseSessionManager sseSessionManager;
    private final LunaStatusPublisher statusPublisher;

    private final ContextAssembler contextAssembler;
    private final ToolSemanticAgent toolSemanticAgent;
    private final SummaryAgent summaryAgent;
    private final RuntimeAuditService runtimeAuditService;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final SessionService sessionService;
    private final EventIngressService eventIngressService;
    private final TaskOrchestratorService taskOrchestratorService;

    @Override
    public void createTaskAndInterrupt(String sessionId, Resource resource, String argsJson) {
        String taskId = SnowflakeIdUtil.nextIdStr();
        long now = System.currentTimeMillis();

        ToolCallingContext callingContext = ToolCallingContextHolder.get();
        ApprovalTask task = ApprovalTask.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .resourceId(parseLong(resource == null ? null : resource.getId()))
                .status(STATUS_PENDING_APPROVAL)
                .skillName(resource == null ? "" : resource.getName())
                .serverCode(resource == null ? "" : resource.getServerCode())
                .toolName(resource == null ? "" : resource.getName())
                .argsJson(argsJson)
                .createTime(now)
                .updateTime(now)
                .chatSessionKey(callingContext != null ? callingContext.getChatSessionKey() : null)
                .userInput(callingContext != null ? callingContext.getUserInput() : null)
                .memorySnippets(callingContext != null ? callingContext.getMemorySnippets() : null)
                .knowledgeSnippets(callingContext != null ? callingContext.getKnowledgeSnippets() : null)
                .preferenceSnippets(callingContext != null ? callingContext.getPreferenceSnippets() : null)
                .longTermMemorySnippets(callingContext != null ? callingContext.getLongTermMemorySnippets() : null)
                .build();

        persistPendingApprovalTask(task);
        redisTemplate.opsForValue().set(REDIS_PREFIX + taskId, task, EXPIRE_MINUTES, TimeUnit.MINUTES);
        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, "APPROVAL_REQUEST", task);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "PENDING_APPROVAL", "Approval required.");
        throw new NeedApprovalException(task);
    }

    @Override
    public String processApproval(String taskId, boolean approved) {
        ApprovalTask task = loadApprovalTask(taskId);
        if (task == null) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return errorJson("approval task not found or expired");
        }
        if (task.getSessionId() != null && !task.getSessionId().isBlank()) {
            eventIngressService.ingestApproval(task.getSessionId(), Map.of("taskId", taskId, "approved", approved));
        }

        String resultJson;
        if (approved) {
            updateTaskStatus(taskId, STATUS_RUNNING, null, null);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", "Running approved tool...");
            String toolResult = executeApprovedTool(task);
            boolean toolFailed = isErrorToolResult(toolResult);
            resultJson = continueChatAfterToolDecision(task, toolResult, true);
            updateTaskStatus(taskId, toolFailed ? STATUS_FAILED : STATUS_COMPLETED, resultJson, toolFailed ? "TOOL_EXECUTION_FAILED" : null);
        } else {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", "Approval rejected, continue without tool.");
            resultJson = continueChatAfterToolDecision(task, null, false);
            updateTaskStatus(taskId, STATUS_REJECTED, resultJson, "USER_REJECTED");
        }

        redisTemplate.delete(REDIS_PREFIX + taskId);
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
            updateTaskStatus(task.getTaskId(), STATUS_FAILED, null, "APPROVAL_TOOL_NAME_MISSING");
            return errorJson("approval task missing tool name");
        }
        try {
            McpToolCallResult result = mcpService.callTool(task.getServerCode(), toolName, task.getArgsJson());
            if (result == null) {
                updateTaskStatus(task.getTaskId(), STATUS_FAILED, null, "TOOL_EXECUTION_NULL");
                return errorJson("tool execution returned null");
            }
            if (result.getRawResult() != null && !result.getRawResult().isBlank()) {
                return result.getRawResult();
            }
            return objectMapper.writeValueAsString(result.getData() == null ? Map.of("status", "success") : result.getData());
        } catch (Exception e) {
            updateTaskStatus(task.getTaskId(), STATUS_FAILED, null, "TOOL_EXECUTION_FAILED");
            return errorJson("approval execute tool failed: " + e.getMessage());
        }
    }

    private void persistPendingApprovalTask(ApprovalTask task) {
        if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
            return;
        }
        try {
            String payloadJson = objectMapper.writeValueAsString(task);
            jdbcTemplate.update(
                    """
                    insert into tasks (task_id, resource_id, status, server_code, tool_name, approval_id, session_id, input_args, approval_payload)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (task_id) do update set
                        resource_id = excluded.resource_id,
                        status = excluded.status,
                        server_code = excluded.server_code,
                        tool_name = excluded.tool_name,
                        approval_id = excluded.approval_id,
                        session_id = excluded.session_id,
                        input_args = excluded.input_args,
                        approval_payload = excluded.approval_payload,
                        updated_at = current_timestamp
                    """,
                    Long.parseLong(task.getTaskId()),
                    task.getResourceId(),
                    STATUS_PENDING_APPROVAL,
                    task.getServerCode(),
                    task.getToolName(),
                    task.getTaskId(),
                    task.getSessionId(),
                    task.getArgsJson(),
                    payloadJson
            );
        } catch (Exception e) {
            throw new IllegalStateException("persist approval task failed", e);
        }
    }

    private ApprovalTask loadApprovalTask(String taskId) {
        ApprovalTask cached = (ApprovalTask) redisTemplate.opsForValue().get(REDIS_PREFIX + taskId);
        if (cached != null) {
            return cached;
        }
        Long numericTaskId = parseLong(taskId);
        if (numericTaskId == null) {
            return null;
        }
        try {
            return jdbcTemplate.query(
                    """
                    select task_id, resource_id, status, server_code, tool_name, session_id, input_args, result, error_code, approval_payload
                    from tasks
                    where task_id = ?
                    limit 1
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        ApprovalTask dbTask = parseApprovalPayload(rs.getString("approval_payload"));
                        if (dbTask == null) {
                            dbTask = new ApprovalTask();
                        }
                        dbTask.setTaskId(String.valueOf(rs.getLong("task_id")));
                        dbTask.setResourceId((Long) rs.getObject("resource_id"));
                        dbTask.setStatus(rs.getString("status"));
                        dbTask.setServerCode(rs.getString("server_code"));
                        dbTask.setToolName(rs.getString("tool_name"));
                        dbTask.setSessionId(rs.getString("session_id"));
                        dbTask.setArgsJson(rs.getString("input_args"));
                        dbTask.setResult(rs.getString("result"));
                        dbTask.setErrorCode(rs.getString("error_code"));
                        return dbTask;
                    },
                    numericTaskId
            );
        } catch (Exception e) {
            return null;
        }
    }

    private ApprovalTask parseApprovalPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ApprovalTask.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private void updateTaskStatus(String taskId, String status, String result, String errorCode) {
        Long numericTaskId = parseLong(taskId);
        if (numericTaskId == null || status == null || status.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    update tasks
                    set status = ?,
                        result = coalesce(?, result),
                        error_code = ?,
                        updated_at = current_timestamp
                    where task_id = ?
                    """,
                    status,
                    result,
                    errorCode,
                    numericTaskId
            );
        } catch (Exception ignore) {
        }
    }

    private Long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isErrorToolResult(String toolResult) {
        JsonNode node = safeToJsonNode(toolResult);
        if (node == null) {
            return false;
        }
        String status = node.path("status").asText("");
        return "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status);
    }

    private String continueChatAfterToolDecision(ApprovalTask task, String toolContext, boolean approved) {
        if (!canContinueChat(task)) {
            if (!approved) {
                return errorJson("User denied the operation.");
            }
            return wrapToolResultAsReply(toolContext);
        }
        try {
            TaskOrchestrationResult orchestrationResult = taskOrchestratorService.orchestrateSystemRecovery(
                    task.getSessionId(),
                    task.getUserInput(),
                    "SYSTEM",
                    Map.of("recovery_event", "APPROVAL_RESUME", "approval_result", approved ? "approved" : "rejected"),
                    "APPROVAL_RESUME",
                    approved ? "APPROVED_BY_USER" : "REJECTED_BY_USER"
            );
            OrchestrationDecision recoveryDecision = orchestrationResult == null ? null : orchestrationResult.getDecision();
            StructuredContextPackage contextPackage = orchestrationResult == null ? null : orchestrationResult.getContextPackage();
            InputReconstructionResult reconstructionResult = orchestrationResult == null ? null : orchestrationResult.getReconstructionResult();
            ToolSemanticResult toolSemanticResult = toolSemanticAgent.translate(
                    approved ? toolContext : "",
                    recoveryDecision == null ? null : recoveryDecision.getTaskState(),
                    reconstructionResult == null ? "" : reconstructionResult.getExplicitTaskGoal()
            );
            AssembledContext assembledContext = contextAssembler.assemble(
                    contextPackage,
                    reconstructionResult,
                    null,
                    toolSemanticResult,
                    task.getUserInput(),
                    task.getMemorySnippets() != null ? task.getMemorySnippets() : Collections.emptyList(),
                    task.getKnowledgeSnippets() != null ? task.getKnowledgeSnippets() : Collections.emptyList(),
                    task.getPreferenceSnippets() != null ? task.getPreferenceSnippets() : Collections.emptyList(),
                    task.getLongTermMemorySnippets() != null ? task.getLongTermMemorySnippets() : Collections.emptyList(),
                    approved ? toolContext : null
            );
            runtimeAuditService.persistFinalContextSnapshot(
                    task.getSessionId(),
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    assembledContext,
                    assembledContext == null ? "" : assembledContext.getPrompt(),
                    assembledContext == null ? Map.of() : assembledContext.getSectionTokenCounts(),
                    assembledContext == null ? Map.of() : assembledContext.getSectionTokenRatios()
            );
            String prompt = assembledContext == null || assembledContext.getPrompt() == null || assembledContext.getPrompt().isBlank()
                    ? PromptTemplates.SYSTEM_PROMPT + "\n\n" + PromptTemplates.RUNTIME_PROMPT.formatted(task.getUserInput())
                    : assembledContext.getPrompt();
            SendToLuna result = getSendToLuna(prompt, task.getUserInput());
            SummaryResult summaryResult = summaryAgent.summarize(task.getUserInput(), result.replyText(), contextPackage);
            runtimeAuditService.persistDecisionRecord(
                    task.getSessionId(),
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_SUMMARY",
                    "approval recovery summary",
                    objectMapper.writeValueAsString(summaryResult)
            );
            sessionService.appendMessage(task.getChatSessionKey(), new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
            return result.valid();
        } catch (Exception e) {
            log.error("approval continue chat failed: {}", e.getMessage(), e);
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

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return null;
        }
        Object session = contextPackage.getRuntime().get("session");
        if (session instanceof Map<?, ?> row) {
            return parseLongValue(row.get("current_plan_id"));
        }
        return null;
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return null;
        }
        Object working = contextPackage.getTaskContext().get("working_memory");
        if (working instanceof Map<?, ?> row) {
            return parseLongValue(row.get("active_node_id"));
        }
        return null;
    }

    private Long parseLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
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
                        return new SendToLuna(raw, removeThoughtFromJson(raw), repairedNode.get(ModelHintConstant.REPLY).asText());
                    }
                }
            } catch (Exception ignore) {
            }
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }
        String raw = node.toString();
        return new SendToLuna(raw, removeThoughtFromJson(raw), node.get(ModelHintConstant.REPLY).asText());
    }

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) {
            return null;
        }
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
        return "{\"thought\":\"fallback\",\"emotion\":\"Solemn\",\"reply\":\"Generation failed, please retry.\"}";
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
        } catch (Exception ignore) {
        }
        return json;
    }

    private String wrapToolResultAsReply(String toolResult) {
        try {
            String replyText = "Operation finished.";
            JsonNode toolNode = safeToJsonNode(toolResult);
            if (toolNode != null) {
                if (toolNode.has("message")) {
                    replyText = toolNode.get("message").asText(replyText);
                } else if (toolNode.has("data")) {
                    replyText = "Operation finished: " + toolNode.get("data");
                } else {
                    replyText = "Operation finished: " + toolNode;
                }
            } else if (toolResult != null && !toolResult.isBlank()) {
                replyText = "Operation finished: " + toolResult;
            }
            return objectMapper.writeValueAsString(Map.of("emotion", "Smile", "reply", replyText));
        } catch (Exception e) {
            return "{\"emotion\":\"Smile\",\"reply\":\"Operation finished.\"}";
        }
    }

    private JsonNode safeToJsonNode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String errorJson(String msg) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", msg,
                    "emotion", "Solemn",
                    "reply", "Operation failed: " + msg
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + msg + "\",\"emotion\":\"Solemn\",\"reply\":\"Operation failed.\"}";
        }
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
