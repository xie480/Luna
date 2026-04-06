package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.ApprovalConstants;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.ResultStatusConstants;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.ApprovalTask;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.ApprovalTaskStatusEnum;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.mapper.ApprovalTaskMapper;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.service.ApprovalService;
import org.yilena.luna.service.McpService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.StateDrivenContextPipeline;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.ContextSnapshot;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextSnapshotStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.sse.SseSessionManager;
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

    private final RedisTemplate<String, Object> redisTemplate;
    private final ApprovalTaskMapper approvalTaskMapper;
    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final SseSessionManager sseSessionManager;
    private final LunaStatusPublisher statusPublisher;

    private final RuntimeAuditService runtimeAuditService;
    private final SessionService sessionService;
    private final EventIngressService eventIngressService;
    private final MemoryWritePipelineService memoryWritePipelineService;
    private final TaskOrchestratorService taskOrchestratorService;
    private final StateDrivenContextPipeline stateDrivenContextPipeline;
    private final ContextSnapshotStore contextSnapshotStore;
    private final RecoveryStateStore recoveryStateStore;
    private final SessionRuntimeMapper sessionRuntimeMapper;

    @Override
    public void createTaskAndInterrupt(String sessionId, Resource resource, String argsJson) {
        String taskId = SnowflakeIdUtil.nextIdStr();
        long now = System.currentTimeMillis();

        ToolCallingContext callingContext = ToolCallingContextHolder.get();
        ApprovalTask task = ApprovalTask.builder()
                .taskId(taskId)
                .sessionId(sessionId)
                .resourceId(parseLong(resource == null ? null : resource.getId()))
                .status(ApprovalTaskStatusEnum.PENDING_APPROVAL.getCode())
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
        writeRecoveryStateOnInterrupt(sessionId, taskId, resource);
        redisTemplate.opsForValue().set(ApprovalConstants.REDIS_PREFIX + taskId, task, ApprovalConstants.EXPIRE_MINUTES, TimeUnit.MINUTES);
        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, ApprovalConstants.EVENT_APPROVAL_REQUEST, task);
        statusPublisher.publish(
                LunaStatusPublisher.DEFAULT_CLIENT_ID,
                ApprovalTaskStatusEnum.PENDING_APPROVAL.getCode(),
                ApprovalConstants.MESSAGE_APPROVAL_REQUIRED
        );
        throw new NeedApprovalException(task);
    }

    @Override
    public String processApproval(String taskId, boolean approved) {
        ApprovalTask task = loadApprovalTask(taskId);
        if (task == null) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "IDLE", "");
            return errorJson(ApprovalConstants.MESSAGE_APPROVAL_TASK_NOT_FOUND);
        }
        if (task.getSessionId() != null && !task.getSessionId().isBlank()) {
            eventIngressService.ingestApproval(task.getSessionId(), Map.of(
                    JsonFieldConstants.TASK_ID, taskId,
                    JsonFieldConstants.APPROVED, approved
            ));
        }

        String resultJson;
        if (approved) {
            updateTaskStatus(taskId, ApprovalTaskStatusEnum.RUNNING.getCode(), null, null);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", ApprovalConstants.MESSAGE_RUNNING_APPROVED_TOOL);
            String toolResult = executeApprovedTool(task);
            boolean toolFailed = isErrorToolResult(toolResult);
            resultJson = continueChatAfterToolDecision(task, toolResult, true);
            updateTaskStatus(
                    taskId,
                    toolFailed ? ApprovalTaskStatusEnum.FAILED.getCode() : ApprovalTaskStatusEnum.COMPLETED.getCode(),
                    resultJson,
                    toolFailed ? ApprovalConstants.ERROR_TOOL_EXECUTION_FAILED : null
            );
        } else {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, "THINKING", ApprovalConstants.MESSAGE_REJECTED_CONTINUE);
            resultJson = continueChatAfterToolDecision(task, null, false);
            updateTaskStatus(taskId, ApprovalTaskStatusEnum.REJECTED.getCode(), resultJson, ApprovalConstants.ERROR_USER_REJECTED);
        }

        redisTemplate.delete(ApprovalConstants.REDIS_PREFIX + taskId);
        sseSessionManager.send(LunaStatusPublisher.DEFAULT_CLIENT_ID, ApprovalConstants.EVENT_APPROVAL_RESULT, Map.of(
                JsonFieldConstants.TASK_ID, taskId,
                JsonFieldConstants.APPROVED, approved,
                JsonFieldConstants.RESULT, safeToJsonNode(resultJson) != null ? safeToJsonNode(resultJson) : resultJson
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
            updateTaskStatus(task.getTaskId(), ApprovalTaskStatusEnum.FAILED.getCode(), null, ApprovalConstants.ERROR_APPROVAL_TOOL_NAME_MISSING);
            return errorJson(ApprovalConstants.MESSAGE_MISSING_TOOL_NAME);
        }
        try {
            McpToolCallResult result = mcpService.callTool(task.getServerCode(), toolName, task.getArgsJson());
            if (result == null) {
                updateTaskStatus(task.getTaskId(), ApprovalTaskStatusEnum.FAILED.getCode(), null, ApprovalConstants.ERROR_TOOL_EXECUTION_NULL);
                return errorJson(ApprovalConstants.MESSAGE_TOOL_NULL);
            }
            if (result.getRawResult() != null && !result.getRawResult().isBlank()) {
                return result.getRawResult();
            }
            return objectMapper.writeValueAsString(result.getData() == null
                    ? Map.of(JsonFieldConstants.STATUS, ResultStatusConstants.SUCCESS)
                    : result.getData());
        } catch (Exception e) {
            updateTaskStatus(task.getTaskId(), ApprovalTaskStatusEnum.FAILED.getCode(), null, ApprovalConstants.ERROR_TOOL_EXECUTION_FAILED);
            return errorJson(ApprovalConstants.MESSAGE_EXECUTE_TOOL_FAILED_PREFIX + e.getMessage());
        }
    }

    private void persistPendingApprovalTask(ApprovalTask task) {
        if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
            return;
        }
        try {
            Long numericTaskId = parseLong(task.getTaskId());
            if (numericTaskId == null) {
                return;
            }
            String payloadJson = objectMapper.writeValueAsString(task);
            approvalTaskMapper.upsertTask(
                    numericTaskId,
                    task.getResourceId(),
                    ApprovalTaskStatusEnum.PENDING_APPROVAL.getCode(),
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

    private void writeRecoveryStateOnInterrupt(String sessionId, String approvalTaskId, Resource resource) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String snapshotId = "";
            ContextSnapshot latestSnapshot = contextSnapshotStore.loadLatest(sessionId);
            if (latestSnapshot != null && latestSnapshot.getSnapshotId() != null && !latestSnapshot.getSnapshotId().isBlank()) {
                snapshotId = latestSnapshot.getSnapshotId();
            }
            String toolName = resource == null ? "" : safe(resource.getName());
            RecoveryState interruptState = RecoveryState.builder()
                    .interruptedAt(String.valueOf(System.currentTimeMillis()))
                    .interruptReason("APPROVAL_PENDING:" + toolName)
                    .recoveryEvent("APPROVAL_INTERRUPT")
                    .recoverySnapshotId(snapshotId)
                    .build();
            recoveryStateStore.save(sessionId, interruptState);
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    null,
                    null,
                    "RECOVERY_STATE_WRITTEN_ON_INTERRUPT",
                    "recovery state persisted at interrupt boundary",
                    objectMapper.writeValueAsString(Map.of(
                            "approvalTaskId", approvalTaskId == null ? "" : approvalTaskId,
                            "snapshotId", snapshotId,
                            "toolName", toolName
                    ))
            );
        } catch (Exception ignore) {
        }
    }

    private ApprovalTask loadApprovalTask(String taskId) {
        ApprovalTask cached = (ApprovalTask) redisTemplate.opsForValue().get(ApprovalConstants.REDIS_PREFIX + taskId);
        if (cached != null) {
            return cached;
        }
        Long numericTaskId = parseLong(taskId);
        if (numericTaskId == null) {
            return null;
        }
        try {
            Map<String, Object> row = approvalTaskMapper.selectTaskById(numericTaskId);
            if (row == null || row.isEmpty()) {
                return null;
            }
            ApprovalTask dbTask = parseApprovalPayload(nullableText(row.get("approval_payload")));
            if (dbTask == null) {
                dbTask = new ApprovalTask();
            }
            Long taskIdValue = parseLongValue(row.get("task_id"));
            dbTask.setTaskId(taskIdValue == null ? String.valueOf(numericTaskId) : String.valueOf(taskIdValue));
            dbTask.setResourceId(parseLongValue(row.get("resource_id")));
            dbTask.setStatus(nullableText(row.get("status")));
            dbTask.setServerCode(nullableText(row.get("server_code")));
            dbTask.setToolName(nullableText(row.get("tool_name")));
            dbTask.setSessionId(nullableText(row.get("session_id")));
            dbTask.setArgsJson(nullableText(row.get("input_args")));
            dbTask.setResult(nullableText(row.get("result")));
            dbTask.setErrorCode(nullableText(row.get("error_code")));
            return dbTask;
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
            approvalTaskMapper.updateTaskStatus(numericTaskId, status, result, errorCode);
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
        String status = node.path(JsonFieldConstants.STATUS).asText("");
        return ResultStatusConstants.ERROR.equalsIgnoreCase(status)
                || ApprovalTaskStatusEnum.FAILED.getCode().equalsIgnoreCase(status);
    }

    private String continueChatAfterToolDecision(ApprovalTask task, String toolContext, boolean approved) {
        if (!canContinueChat(task)) {
            if (!approved) {
                return errorJson(ApprovalConstants.MESSAGE_USER_DENIED_OPERATION);
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
            RoundPipelineRequest roundPipelineRequest = RoundPipelineRequest.builder()
                            .sessionId(task.getSessionId())
                            .userInput(task.getUserInput())
                            .decision(recoveryDecision)
                            .contextPackage(contextPackage)
                            .reconstructionResult(reconstructionResult)
                            .nodeWorksetResult(nodeWorksetResult)
                            .workingMemorySnippets(workingMemorySnippets)
                            .runtimeMemorySnippets(runtimeMemorySnippets)
                            .retrievedMemorySnippets(retrievedMemorySnippets)
                            .knowledgeSnippets(mergeDistinct(
                                    task.getKnowledgeSnippets() != null ? task.getKnowledgeSnippets() : Collections.emptyList(),
                                    selectedKnowledgeSnippets
                            ))
                            .preferenceSnippets(mergeDistinct(
                                    task.getPreferenceSnippets() != null ? task.getPreferenceSnippets() : Collections.emptyList(),
                                    selectedPreferenceSnippets
                            ))
                            .longTermMemorySnippets(task.getLongTermMemorySnippets() != null ? task.getLongTermMemorySnippets() : Collections.emptyList())
                            .executionCandidates(executionCandidates)
                            .mcpResourceHints(mcpResourceHints)
                            .nodeTemplatePolicy(nodeTemplatePolicy)
                            .toolContext(approved ? toolContext : "")
                            .stage("APPROVAL_RECOVERY")
                            .repairSeed(task.getUserInput())
                            .runMainModel(true)
                            .assistantReplyOverride("")
                            .preAssemblyTriggerSource("APPROVAL_PRE_ASSEMBLY")
                            .postSummaryTriggerSource("APPROVAL_RECOVERY")
                            .replaceHistoryWithSummary(true)
                            .writeRoundState(true)
                            .rawToolResultChannel(buildRawToolResultChannel(
                                    approved ? toolContext : "",
                                    approved ? List.of(Map.of("rawResult", safe(toolContext))) : List.of(),
                                    "",
                                    List.of()
                            ))
                            .retrievalPlanOverrides(Map.of("approvalRecovery", true))
                            .build();
            RoundPipelineResult roundPipelineResult = stateDrivenContextPipeline.run(
                    StateDrivenContextPipelineRequest.builder()
                            .sessionId(task.getSessionId())
                            .triggerSource("APPROVAL_RECOVERY")
                            .roundPipelineRequest(roundPipelineRequest)
                            .build()
            );
            MainModelOrchestrationResult modelResult = roundPipelineResult == null ? null : roundPipelineResult.getMainModelResult();
            if (roundPipelineResult == null || roundPipelineResult.isBlocked() || modelResult == null || modelResult.isBlocked()) {
                return errorJson(ApprovalConstants.MESSAGE_GOVERNANCE_BLOCKED);
            }
            memoryWritePipelineService.writeAfterTurn(
                    task.getSessionId(),
                    task.getUserInput(),
                    modelResult.getReplyText(),
                    contextPackage
            );
            sessionService.appendMessage(task.getChatSessionKey(), new ChatMessage(ChatMessage.Role.LUNA, modelResult.getReplyText(), LocalTime.now()));
            return modelResult.getValidResponse();
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
            if (contextPackage == null || contextPackage.getTaskStateEntity() == null) {
                return null;
            }
            return parseLongValue(contextPackage.getTaskStateEntity().getTaskId());
        }
        Object session = contextPackage.getRuntime().get("session");
        if (session instanceof Map<?, ?> row) {
            Long runtimePlan = parseLongValue(row.get("current_plan_id"));
            if (runtimePlan != null) {
                return runtimePlan;
            }
        }
        return contextPackage.getTaskStateEntity() == null ? null : parseLongValue(contextPackage.getTaskStateEntity().getTaskId());
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            if (contextPackage == null || contextPackage.getTaskStateEntity() == null) {
                return null;
            }
            return parseLongValue(contextPackage.getTaskStateEntity().getCurrentNode());
        }
        Object working = contextPackage.getTaskContext().get("working_memory");
        if (working instanceof Map<?, ?> row) {
            Long runtimeNode = parseLongValue(row.get("active_node_id"));
            if (runtimeNode != null) {
                return runtimeNode;
            }
        }
        return contextPackage.getTaskStateEntity() == null ? null : parseLongValue(contextPackage.getTaskStateEntity().getCurrentNode());
    }

    private Long parseLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignore) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(-?\\d+)").matcher(text);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (Exception nestedIgnore) {
                    return null;
                }
            }
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
        String nodeKind = resolveCurrentNodeKind(contextPackage);
        return ContextNodeTemplatePolicy.forTaskNode(taskState, currentNode, nodeKind);
    }

    private String resolveCurrentNodeKind(StructuredContextPackage contextPackage) {
        Long planId = contextPlanId(contextPackage);
        Long nodeId = contextNodeId(contextPackage);
        if (planId == null || nodeId == null) {
            return "";
        }
        try {
            String nodeType = sessionRuntimeMapper.selectNodeTypeByPlanAndNode(planId, nodeId);
            return nodeType == null ? "" : nodeType.trim();
        } catch (Exception ignore) {
            return "";
        }
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

    private Map<String, Object> buildRawToolResultChannel(String rawToolContext,
                                                          List<Map<String, Object>> rawToolExecutionTraces,
                                                          String latestToolRawRef,
                                                          List<String> toolHistoryRefs) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("rawToolContext", rawToolContext == null ? "" : rawToolContext);
        channel.put("rawToolExecutionTraces", rawToolExecutionTraces == null ? List.of() : rawToolExecutionTraces);
        channel.put("latestToolRawRef", latestToolRawRef == null ? "" : latestToolRawRef);
        channel.put("toolHistoryRefs", toolHistoryRefs == null ? List.of() : toolHistoryRefs);
        return channel;
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
            String traceId = stringValue(row.get("trace_id"));
            if (!traceId.isBlank()) {
                out.add("tool_execution_trace:id=" + traceId);
                continue;
            }
            String name = stringValue(row.get("tool_name"));
            String status = stringValue(row.get("call_status"));
            if (!name.isBlank()) {
                out.add("tool_execution_trace:" + name + ":" + status);
            }
        }
        if (out.isEmpty()) {
            out.add("tool_execution_trace:latest");
        }
        return out.stream().distinct().toList();
    }

    private String resolveLatestToolRawResultRef(List<Map<String, Object>> toolRows, ToolState previousToolState) {
        if (toolRows != null && !toolRows.isEmpty()) {
            String traceId = stringValue(toolRows.get(0).get("trace_id"));
            if (!traceId.isBlank()) {
                return "tool_execution_trace:id=" + traceId;
            }
            String name = stringValue(toolRows.get(0).get("tool_name"));
            String status = stringValue(toolRows.get(0).get("call_status"));
            if (!name.isBlank()) {
                return "tool_execution_trace:" + name + ":" + status;
            }
        }
        if (previousToolState != null && previousToolState.getLastToolRawResultRef() != null && !previousToolState.getLastToolRawResultRef().isBlank()) {
            return previousToolState.getLastToolRawResultRef();
        }
        return "tool_execution_trace:latest";
    }

    private List<String> resolveActiveToolEvidenceRefs(List<Map<String, Object>> toolRows, ContextState previousContextState) {
        List<String> refs = new ArrayList<>();
        refs.addAll(extractToolHistoryRefs(toolRows));
        if (refs.isEmpty() && previousContextState != null && previousContextState.getActiveToolEvidenceRefs() != null) {
            refs.addAll(previousContextState.getActiveToolEvidenceRefs());
        }
        if (refs.isEmpty()) {
            refs.add("tool_execution_trace:latest");
        }
        return refs.stream().filter(ref -> ref != null && !ref.isBlank()).distinct().toList();
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

    private String nullableText(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private String wrapToolResultAsReply(String toolResult) {
        try {
            String replyText = "Operation finished.";
            JsonNode toolNode = safeToJsonNode(toolResult);
            if (toolNode != null) {
                if (toolNode.has(JsonFieldConstants.MESSAGE)) {
                    replyText = toolNode.get(JsonFieldConstants.MESSAGE).asText(replyText);
                } else if (toolNode.has(JsonFieldConstants.DATA)) {
                    replyText = "Operation finished: " + toolNode.get(JsonFieldConstants.DATA);
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
                    JsonFieldConstants.STATUS, ResultStatusConstants.ERROR,
                    JsonFieldConstants.MESSAGE, msg,
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

}
