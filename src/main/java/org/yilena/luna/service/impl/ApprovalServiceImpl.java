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
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.ApprovalTask;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.TaskRuntimeState;
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
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextSnapshotStore;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.sse.SseSessionManager;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.SnowflakeIdUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final ContextTraceLogger contextTraceLogger;
    private final ToolSemanticAgent toolSemanticAgent;
    private final ToolSemanticResultValidator toolSemanticResultValidator;
    private final ToolSemanticTraceLogger toolSemanticTraceLogger;
    private final SummaryAgent summaryAgent;
    private final RuntimeAuditService runtimeAuditService;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final SessionService sessionService;
    private final EventIngressService eventIngressService;
    private final TaskOrchestratorService taskOrchestratorService;
    private final TaskStateStore taskStateStore;
    private final RetrievalStateStore retrievalStateStore;
    private final ToolStateStore toolStateStore;
    private final ContextStateStore contextStateStore;
    private final ContextSnapshotStore contextSnapshotStore;

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
            NodeWorksetResult nodeWorksetResult = taskOrchestratorService.orchestrateNodeWorkset(
                    task.getSessionId(),
                    task.getUserInput(),
                    recoveryDecision,
                    contextPackage,
                    reconstructionResult
            );
            ContextRerankResult rerankResult = nodeWorksetResult == null ? null : nodeWorksetResult.getRerankResult();
            List<String> selectedMemorySnippets = nodeWorksetResult == null || nodeWorksetResult.getSelectedMemorySnippets() == null
                    ? List.of()
                    : nodeWorksetResult.getSelectedMemorySnippets();
            List<String> selectedKnowledgeSnippets = nodeWorksetResult == null || nodeWorksetResult.getSelectedKnowledgeSnippets() == null
                    ? List.of()
                    : nodeWorksetResult.getSelectedKnowledgeSnippets();
            List<String> selectedPreferenceSnippets = nodeWorksetResult == null || nodeWorksetResult.getSelectedPreferenceSnippets() == null
                    ? List.of()
                    : nodeWorksetResult.getSelectedPreferenceSnippets();
            List<EvidenceBlock> selectedKnowledgeEvidenceBlocks = nodeWorksetResult == null || nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks() == null
                    ? List.of()
                    : nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks();
            List<Resource> executionCandidates = nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null
                    ? List.of()
                    : nodeWorksetResult.getExecutionCandidates();
            List<String> mcpResourceHints = nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null
                    ? List.of()
                    : nodeWorksetResult.getMcpResourceHints();
            List<String> workingMemorySnippets = task.getMemorySnippets() != null ? task.getMemorySnippets() : Collections.emptyList();
            List<String> runtimeMemorySnippets = extractRuntimeMessageSnippets(contextPackage);
            List<String> retrievedMemorySnippets = selectedMemorySnippets;
            ContextNodeTemplatePolicy nodeTemplatePolicy = resolveNodeTemplatePolicy(recoveryDecision, contextPackage);
            ToolSemanticResult toolSemanticResult = toolSemanticAgent.translate(
                    resolvePrimaryToolName(executionCandidates),
                    resolvePrimaryToolDescription(executionCandidates),
                    approved ? toolContext : "",
                    recoveryDecision == null ? null : recoveryDecision.getTaskState(),
                    reconstructionResult == null ? "" : reconstructionResult.getExplicitTaskGoal()
            );
            ToolSemanticResultValidator.ValidationResult semanticValidation = toolSemanticResultValidator.validate(toolSemanticResult, contextPackage);
            if (semanticValidation.valid()) {
                runtimeAuditService.persistDecisionRecord(
                        task.getSessionId(),
                        contextPlanId(contextPackage),
                        contextNodeId(contextPackage),
                        "TOOL_SEMANTIC_VALIDATION",
                        "approval recovery semantic channel validation passed",
                        "{}"
                );
            } else {
                runtimeAuditService.persistDecisionRecord(
                        task.getSessionId(),
                        contextPlanId(contextPackage),
                        contextNodeId(contextPackage),
                        "TOOL_SEMANTIC_VALIDATION",
                        "approval recovery semantic channel validation failed",
                        objectMapper.writeValueAsString(Map.of("issues", semanticValidation.issues()))
                );
            }
            toolSemanticResult = semanticValidation.normalized() == null ? toolSemanticResult : semanticValidation.normalized();
            toolSemanticTraceLogger.log(task.getSessionId(), contextPlanId(contextPackage), contextNodeId(contextPackage), toolSemanticResult);
            AssembledContext assembledContext = contextAssembler.assemble(
                    contextPackage,
                    reconstructionResult,
                    rerankResult,
                    toolSemanticResult,
                    task.getUserInput(),
                    selectedKnowledgeEvidenceBlocks,
                    workingMemorySnippets,
                    runtimeMemorySnippets,
                    retrievedMemorySnippets,
                    mergeDistinct(
                            task.getKnowledgeSnippets() != null ? task.getKnowledgeSnippets() : Collections.emptyList(),
                            selectedKnowledgeSnippets
                    ),
                    mergeDistinct(
                            task.getPreferenceSnippets() != null ? task.getPreferenceSnippets() : Collections.emptyList(),
                            selectedPreferenceSnippets
                    ),
                    task.getLongTermMemorySnippets() != null ? task.getLongTermMemorySnippets() : Collections.emptyList(),
                    executionCandidates,
                    mcpResourceHints,
                    approved ? toolContext : null,
                    nodeTemplatePolicy
            );
            contextTraceLogger.log(task.getSessionId(), contextPlanId(contextPackage), contextNodeId(contextPackage), assembledContext);
            String finalSnapshotId = contextSnapshotStore.saveFinalSnapshot(
                    task.getSessionId(),
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    assembledContext,
                    assembledContext == null ? "" : assembledContext.getPrompt(),
                    assembledContext == null ? Map.of() : assembledContext.getSectionTokenCounts(),
                    assembledContext == null ? Map.of() : assembledContext.getSectionTokenRatios()
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
            SummaryResult summaryResult = summaryAgent.summarize(
                    task.getUserInput(),
                    result.replyText(),
                    contextPackage,
                    selectedKnowledgeEvidenceBlocks,
                    mcpResourceHints,
                    toolSemanticResult
            );
            runtimeAuditService.persistDecisionRecord(
                    task.getSessionId(),
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_SUMMARY",
                    "approval recovery summary",
                    objectMapper.writeValueAsString(summaryResult)
            );
            writeStateStores(
                    task.getSessionId(),
                    recoveryDecision,
                    contextPackage,
                    reconstructionResult,
                    rerankResult,
                    toolSemanticResult,
                    summaryResult,
                    finalSnapshotId,
                    nodeWorksetResult == null ? "" : nodeWorksetResult.getRagQuery(),
                    nodeWorksetResult == null ? "" : nodeWorksetResult.getMemoryQuery(),
                    nodeWorksetResult == null ? "" : nodeWorksetResult.getMcpDrivenInput()
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

    private ContextNodeTemplatePolicy resolveNodeTemplatePolicy(OrchestrationDecision decision, StructuredContextPackage contextPackage) {
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        if (taskState == null && contextPackage != null) {
            taskState = contextPackage.getTaskState();
        }
        String currentNode = "";
        if (contextPackage != null && contextPackage.getTaskStateEntity() != null && contextPackage.getTaskStateEntity().getCurrentNode() != null) {
            currentNode = contextPackage.getTaskStateEntity().getCurrentNode();
        }
        return ContextNodeTemplatePolicy.forTaskStage(taskState, currentNode);
    }

    private List<String> extractRuntimeMessageSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return List.of();
        }
        Object raw = contextPackage.getRuntime().get("recent_messages");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> safe(item.get("role")) + ": " + safe(item.get("content_text")))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<String> mergeDistinct(List<String> left, List<String> right) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return new ArrayList<>(merged);
    }

    private void writeStateStores(String sessionId,
                                  OrchestrationDecision decision,
                                  StructuredContextPackage contextPackage,
                                  InputReconstructionResult reconstruction,
                                  ContextRerankResult rerankResult,
                                  ToolSemanticResult toolSemanticResult,
                                  SummaryResult summaryResult,
                                  String latestSnapshotId,
                                  String ragQuery,
                                  String memoryQuery,
                                  String mcpQuery) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        TaskState previousTaskState = contextPackage == null ? null : contextPackage.getTaskStateEntity();
        RetrievalState previousRetrievalState = contextPackage == null ? null : contextPackage.getRetrievalState();
        ToolState previousToolState = contextPackage == null ? null : contextPackage.getToolState();
        ContextState previousContextState = contextPackage == null ? null : contextPackage.getContextState();
        Map<String, Object> runtime = contextPackage == null ? Map.of() : safeMap(contextPackage.getRuntime());
        Map<String, Object> sessionRow = safeMap(runtime.get("session"));
        List<Map<String, Object>> toolRows = safeMapList(runtime.get("active_tool_results"));
        List<String> finishedSteps = mergeDistinctList(
                previousTaskState == null ? List.of() : previousTaskState.getFinishedSteps(),
                extractToolStepNames(toolRows, "success", "ok", "completed")
        );
        List<String> failedSteps = mergeDistinctList(
                previousTaskState == null ? List.of() : previousTaskState.getFailedSteps(),
                extractToolStepNames(toolRows, "failed", "error")
        );
        int retryCount = deriveRetryCount(previousTaskState, sessionRow, failedSteps);
        Map<String, Object> confirmedSlots = mergeMaps(
                previousTaskState == null ? Map.of() : previousTaskState.getConfirmedSlots(),
                reconstruction == null || reconstruction.getClarifiedEntities() == null ? Map.of() : new LinkedHashMap<>(reconstruction.getClarifiedEntities())
        );
        List<String> pendingQuestions = mergeDistinctList(
                previousTaskState == null ? List.of() : previousTaskState.getPendingQuestions(),
                reconstruction == null || reconstruction.getMissingSlots() == null ? List.of() : reconstruction.getMissingSlots()
        );
        TaskState taskState = TaskState.builder()
                .taskId(String.valueOf(contextPlanId(contextPackage)))
                .sessionId(sessionId)
                .objective(reconstruction == null ? "" : reconstruction.getExplicitTaskGoal())
                .currentStage(decision == null || decision.getTaskState() == null ? "UNKNOWN" : decision.getTaskState().name())
                .currentNode(String.valueOf(contextNodeId(contextPackage)))
                .confirmedSlots(confirmedSlots)
                .pendingQuestions(pendingQuestions)
                .finishedSteps(finishedSteps)
                .failedSteps(failedSteps)
                .retryCount(retryCount)
                .nextActionHint(summaryResult == null || summaryResult.getStateSnapshot() == null ? "continue" : String.valueOf(summaryResult.getStateSnapshot().getOrDefault("nextStep", "continue")))
                .build();
        taskStateStore.save(sessionId, taskState);

        RetrievalState retrievalState = RetrievalState.builder()
                .reconstructedIntent(reconstruction == null ? "" : reconstruction.getNormalizedUserIntent())
                .activeQueries(mergeDistinctList(
                        previousRetrievalState == null ? List.of() : previousRetrievalState.getActiveQueries(),
                        mergeDistinct(
                                mergeDistinct(nonBlankList(ragQuery), nonBlankList(memoryQuery)),
                                mergeDistinct(
                                        nonBlankList(mcpQuery),
                                        reconstruction == null ? List.of() : mergeDistinct(
                                                nonBlankList(reconstruction.getReformulatedQueryForRag()),
                                                nonBlankList(reconstruction.getReformulatedQueryForMcp())
                                        )
                                )
                        )))
                .retrievalPlan(Map.of("approvalRecovery", true))
                .selectedEvidenceRefs(extractKnowledgeRefs(rerankResult))
                .rerankSummary(rerankResult == null ? "" : safe(rerankResult.getRationaleByNode()))
                .build();
        retrievalStateStore.save(sessionId, retrievalState);

        ToolState toolState = ToolState.builder()
                .lastToolName(resolveLastToolName(toolRows, toolSemanticResult))
                .lastToolInput(reconstruction == null ? "" : reconstruction.getReformulatedQueryForMcp())
                .lastToolStatus(toolSemanticResult == null ? "" : toolSemanticResult.getToolStatus())
                .lastToolRawResultRef("tool_execution_trace:latest")
                .lastToolSemanticSummary(toolSemanticResult == null ? "" : toolSemanticResult.getBusinessImpact())
                .toolCallHistoryRefs(mergeDistinctList(
                        previousToolState == null ? List.of() : previousToolState.getToolCallHistoryRefs(),
                        extractToolHistoryRefs(toolRows)
                ))
                .build();
        toolStateStore.save(sessionId, toolState);

        ContextState contextState = ContextState.builder()
                .latestNarrativeSummary(summaryResult == null ? "" : summaryResult.getNarrativeSummary())
                .latestStateSnapshot(summaryResult == null || summaryResult.getStateSnapshot() == null ? Map.of() : summaryResult.getStateSnapshot())
                .activeKnowledgeRefs(extractKnowledgeRefs(rerankResult))
                .activeMemoryRefs(rerankResult == null || rerankResult.getSelectedMemoryHints() == null ? List.of() : rerankResult.getSelectedMemoryHints())
                .activeToolEvidenceRefs(List.of("tool_execution_trace:latest"))
                .activeMcpPromptRefs(rerankResult == null || rerankResult.getSelectedPromptResources() == null ? List.of() : rerankResult.getSelectedPromptResources().stream().map(this::safe).toList())
                .activeMcpResourceRefs(rerankResult == null || rerankResult.getSelectedToolCandidates() == null ? List.of() : rerankResult.getSelectedToolCandidates().stream().map(this::safe).toList())
                .latestContextSnapshotId(firstNonBlank(latestSnapshotId, previousContextState == null ? "" : previousContextState.getLatestContextSnapshotId()))
                .build();
        contextStateStore.save(sessionId, contextState);
    }

    private List<String> extractKnowledgeRefs(ContextRerankResult rerankResult) {
        if (rerankResult == null) {
            return List.of();
        }
        if (rerankResult.getSelectedKnowledgeEvidenceBlocks() != null && !rerankResult.getSelectedKnowledgeEvidenceBlocks().isEmpty()) {
            return rerankResult.getSelectedKnowledgeEvidenceBlocks().stream()
                    .map(EvidenceBlock::getBlockId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
        }
        if (rerankResult.getSelectedKnowledgeBlocks() != null) {
            return rerankResult.getSelectedKnowledgeBlocks();
        }
        return List.of();
    }

    private String resolvePrimaryToolName(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "agent_tool_chain";
        }
        Resource first = executionCandidates.get(0);
        return first == null || first.getName() == null || first.getName().isBlank() ? "agent_tool_chain" : first.getName();
    }

    private String resolvePrimaryToolDescription(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "";
        }
        Resource first = executionCandidates.get(0);
        if (first == null) {
            return "";
        }
        return "type=" + (first.getType() == null ? "" : first.getType().name())
                + ", server=" + safe(first.getServerCode())
                + ", resourceUri=" + safe(first.getResourceUri());
    }

    private List<String> mergeDistinctList(List<String> left, List<String> right) {
        return mergeDistinct(left == null ? List.of() : left, right == null ? List.of() : right);
    }

    private List<String> extractToolStepNames(List<Map<String, Object>> toolRows, String... statuses) {
        if (toolRows == null || toolRows.isEmpty()) {
            return List.of();
        }
        List<String> expected = new ArrayList<>();
        for (String status : statuses) {
            expected.add(status.toLowerCase());
        }
        return toolRows.stream()
                .filter(row -> expected.contains(stringValue(row.get("call_status")).toLowerCase()))
                .map(row -> stringValue(row.get("tool_name")))
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private int deriveRetryCount(TaskState previousTaskState, Map<String, Object> sessionRow, List<String> failedSteps) {
        int fromPrevious = previousTaskState == null || previousTaskState.getRetryCount() == null ? 0 : previousTaskState.getRetryCount();
        int fromSession = intValue(sessionRow.get("retry_count"));
        int fromFailureSignals = failedSteps == null ? 0 : failedSteps.size();
        return Math.max(fromPrevious, Math.max(fromSession, fromFailureSignals));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignore) {
            return 0;
        }
    }

    private Map<String, Object> mergeMaps(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return merged;
    }

    private String resolveLastToolName(List<Map<String, Object>> toolRows, ToolSemanticResult toolSemanticResult) {
        if (toolRows != null && !toolRows.isEmpty()) {
            String name = stringValue(toolRows.get(0).get("tool_name"));
            if (!name.isBlank()) {
                return name;
            }
        }
        if (toolSemanticResult != null && toolSemanticResult.getToolName() != null && !toolSemanticResult.getToolName().isBlank()) {
            return toolSemanticResult.getToolName();
        }
        return toolSemanticResult == null ? "" : "agent_tool_chain";
    }

    private List<String> extractToolHistoryRefs(List<Map<String, Object>> toolRows) {
        if (toolRows == null || toolRows.isEmpty()) {
            return List.of("tool_execution_trace:latest");
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : toolRows) {
            String name = stringValue(row.get("tool_name"));
            String status = stringValue(row.get("call_status"));
            if (!name.isBlank()) {
                out.add("tool_execution_trace:" + name + ":" + status);
            }
        }
        out.add("tool_execution_trace:latest");
        return out.stream().distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> nonBlankList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value);
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private record SendToLuna(String raw, String valid, String replyText) {
    }
}
