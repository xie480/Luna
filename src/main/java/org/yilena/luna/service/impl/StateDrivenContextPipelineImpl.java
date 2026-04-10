package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.RoundPipelineOrchestrator;
import org.yilena.luna.service.StateDrivenContextPipeline;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;
import org.yilena.luna.service.model.TaskOrchestrationResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 状态驱动上下文流水线实现，负责在任务状态基础上补齐轮次请求并驱动完整的上下文执行链路。
 */
public class StateDrivenContextPipelineImpl implements StateDrivenContextPipeline {

    private final RoundPipelineOrchestrator roundPipelineOrchestrator;
    private final RuntimeAuditService runtimeAuditService;
    private final ObjectProvider<TaskOrchestratorService> taskOrchestratorServiceProvider;
    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final ObjectMapper objectMapper;
    private final StateTransitionTraceLogger stateTransitionTraceLogger;

    @Override
    public RoundPipelineResult run(StateDrivenContextPipelineRequest request) {
        /**
         * 先校验状态驱动请求是否完整，缺少轮次请求时直接返回阻断结果。
         */
        if (request == null || request.getRoundPipelineRequest() == null) {
            return blocked("state_driven_context_pipeline_request_missing");
        }

        /**
         * 对轮次请求做水化，补齐缺失的决策、上下文、重构结果和工作集，形成真正可执行输入。
         */
        RoundPipelineRequest hydratedRoundRequest = hydrateRoundRequest(request);
        if (hydratedRoundRequest == null) {
            return blocked("state_driven_context_pipeline_hydration_failed");
        }

        /**
         * 在执行前按重构、召回、重排、组装四个阶段输出审计钩子，保留状态驱动链路轨迹。
         */
        String sessionId = firstNonBlank(request.getSessionId(), hydratedRoundRequest.getSessionId());
        String triggerSource = firstNonBlank(request.getTriggerSource(), "STATE_DRIVEN_CONTEXT_PIPELINE");
        Long planId = contextPlanId(hydratedRoundRequest);
        Long nodeId = contextNodeId(hydratedRoundRequest);
        String traceId = "state_pipeline:" + sessionId + ":" + System.currentTimeMillis();

        auditHook(traceId, sessionId, planId, nodeId, "reconstruct", triggerSource, hydratedRoundRequest, Map.of(
                "hasReconstruction", hydratedRoundRequest.getReconstructionResult() != null,
                "stage", safe(hydratedRoundRequest.getStage())
        ));
        auditHook(traceId, sessionId, planId, nodeId, "recall", triggerSource, hydratedRoundRequest, Map.of(
                "hasNodeWorkset", hydratedRoundRequest.getNodeWorksetResult() != null,
                "hasExecutionCandidates", hydratedRoundRequest.getExecutionCandidates() != null && !hydratedRoundRequest.getExecutionCandidates().isEmpty(),
                "hasMcpHints", hydratedRoundRequest.getMcpResourceHints() != null && !hydratedRoundRequest.getMcpResourceHints().isEmpty()
        ));
        auditHook(traceId, sessionId, planId, nodeId, "rerank", triggerSource, hydratedRoundRequest, Map.of(
                "hasRerankResult", hydratedRoundRequest.getNodeWorksetResult() != null
                        && hydratedRoundRequest.getNodeWorksetResult().getRerankResult() != null
        ));
        auditHook(traceId, sessionId, planId, nodeId, "assemble", triggerSource, hydratedRoundRequest, Map.of(
                "runMainModel", hydratedRoundRequest.isRunMainModel(),
                "writeRoundState", hydratedRoundRequest.isWriteRoundState()
        ));

        /**
         * 调用轮次流水线执行真正的主流程，并在执行后补记 execute 与 writeback 阶段审计。
         */
        RoundPipelineResult result = roundPipelineOrchestrator.executeRound(hydratedRoundRequest);
        auditHook(traceId, sessionId, planId, nodeId, "execute", triggerSource, hydratedRoundRequest, Map.of(
                "blocked", result != null && result.isBlocked(),
                "blockedReason", result == null ? "round_result_missing" : safe(result.getBlockedReason())
        ));
        auditHook(traceId, sessionId, planId, nodeId, "writeback", triggerSource, hydratedRoundRequest, Map.of(
                "finalSnapshotId", result == null ? "" : safe(result.getFinalSnapshotId()),
                "summaryPresent", result != null && result.getSummaryResult() != null
        ));
        if (result == null) {
            return blocked("round_result_missing");
        }

        /**
         * 结果返回前统一做字段兜底，优先复用轮次执行结果，缺失时回退到水化请求里的上下文。
         */
        return RoundPipelineResult.builder()
                .blocked(result.isBlocked())
                .blockedReason(result.getBlockedReason())
                .toolSemanticResult(result.getToolSemanticResult())
                .preAssemblySummary(result.getPreAssemblySummary())
                .mainModelResult(result.getMainModelResult())
                .summaryResult(result.getSummaryResult())
                .finalSnapshotId(result.getFinalSnapshotId())
                .decision(result.getDecision() == null ? hydratedRoundRequest.getDecision() : result.getDecision())
                .contextPackage(result.getContextPackage() == null ? hydratedRoundRequest.getContextPackage() : result.getContextPackage())
                .reconstructionResult(result.getReconstructionResult() == null ? hydratedRoundRequest.getReconstructionResult() : result.getReconstructionResult())
                .nodeWorksetResult(result.getNodeWorksetResult() == null ? hydratedRoundRequest.getNodeWorksetResult() : result.getNodeWorksetResult())
                .build();
    }

    private RoundPipelineResult blocked(String reason) {
        return RoundPipelineResult.builder()
                .blocked(true)
                .blockedReason(reason)
                .toolSemanticResult(null)
                .preAssemblySummary(null)
                .mainModelResult(null)
                .summaryResult(null)
                .finalSnapshotId("")
                .decision(null)
                .contextPackage(null)
                .reconstructionResult(null)
                .nodeWorksetResult(null)
                .build();
    }

    private RoundPipelineRequest hydrateRoundRequest(StateDrivenContextPipelineRequest request) {
        RoundPipelineRequest input = request.getRoundPipelineRequest();
        if (input == null) {
            return null;
        }

        /**
         * 先整合请求中的会话和用户输入，再读取已有的决策、上下文和重构结果。
         */
        String sessionId = firstNonBlank(request.getSessionId(), input.getSessionId());
        String userInput = safe(input.getUserInput());

        OrchestrationDecision decision = input.getDecision();
        StructuredContextPackage contextPackage = input.getContextPackage();
        InputReconstructionResult reconstructionResult = input.getReconstructionResult();

        if ((decision == null || contextPackage == null || reconstructionResult == null) && !userInput.isBlank()) {
            /**
             * 如果核心上下文缺失，则回退到任务编排服务重新执行一次用户输入编排，补齐基础运行态。
             */
            TaskOrchestrationResult orchestration = taskOrchestratorService().orchestrateUserInput(sessionId, userInput);
            if (decision == null) {
                decision = orchestration == null ? null : orchestration.getDecision();
            }
            if (contextPackage == null) {
                contextPackage = orchestration == null ? null : orchestration.getContextPackage();
            }
            if (reconstructionResult == null) {
                reconstructionResult = orchestration == null ? null : orchestration.getReconstructionResult();
            }
        }
        if (!isReconstructionReady(reconstructionResult)) {
            /**
             * 缺少明确任务目标时阻断轮次执行，因为后续节点工作集和主模型都依赖该目标。
             */
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "STATE_DRIVEN_PIPELINE_BLOCKED",
                    "hydrate round request blocked due to missing reconstruction",
                    toJsonSafe(Map.of(
                            "reason", reconstructionResult == null
                                    ? "input_reconstruction_missing"
                                    : "input_reconstruction_goal_missing",
                            "hasReconstruction", reconstructionResult != null,
                            "explicitTaskGoal", reconstructionResult == null ? "" : safe(reconstructionResult.getExplicitTaskGoal())
                    ))
            );
            return null;
        }

        NodeWorksetResult nodeWorksetResult = input.getNodeWorksetResult();
        if (nodeWorksetResult == null
                && !userInput.isBlank()
                && decision != null
                && contextPackage != null
                && reconstructionResult != null) {
            /**
             * 当节点工作集尚未生成时，基于当前决策和上下文补做节点级检索与候选能力编排。
             */
            nodeWorksetResult = taskOrchestratorService().orchestrateNodeWorkset(
                    sessionId,
                    userInput,
                    decision,
                    contextPackage,
                    reconstructionResult
            );
        }

        List<Resource> executionCandidates = nonEmpty(input.getExecutionCandidates())
                ? input.getExecutionCandidates()
                : (nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null ? List.of() : nodeWorksetResult.getExecutionCandidates());
        List<String> mcpResourceHints = nonEmpty(input.getMcpResourceHints())
                ? input.getMcpResourceHints()
                : (nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null ? List.of() : nodeWorksetResult.getMcpResourceHints());

        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        if (nodeWorksetResult != null && nonEmpty(nodeWorksetResult.getSelectedKnowledgeSnippets())) {
            knowledgeSnippets = nodeWorksetResult.getSelectedKnowledgeSnippets();
        }
        if (nonEmpty(input.getKnowledgeSnippets())) {
            knowledgeSnippets = input.getKnowledgeSnippets();
        }

        List<String> preferenceSnippets = mergeDistinct(
                extractRelationalPreferenceSnippets(contextPackage),
                nodeWorksetResult == null ? List.of() : nodeWorksetResult.getSelectedPreferenceSnippets()
        );
        if (nonEmpty(input.getPreferenceSnippets())) {
            preferenceSnippets = input.getPreferenceSnippets();
        }

        List<String> longTermMemorySnippets = nonEmpty(input.getLongTermMemorySnippets())
                ? input.getLongTermMemorySnippets()
                : extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = nonEmpty(input.getWorkingMemorySnippets())
                ? input.getWorkingMemorySnippets()
                : extractWorkingMemorySnippets(contextPackage);
        List<String> runtimeMemorySnippets = nonEmpty(input.getRuntimeMemorySnippets())
                ? input.getRuntimeMemorySnippets()
                : extractRuntimeMessageSnippets(contextPackage);
        List<String> retrievedMemorySnippets = nonEmpty(input.getRetrievedMemorySnippets())
                ? input.getRetrievedMemorySnippets()
                : (nodeWorksetResult == null || nodeWorksetResult.getSelectedMemorySnippets() == null ? List.of() : nodeWorksetResult.getSelectedMemorySnippets());

        ContextNodeTemplatePolicy nodeTemplatePolicy = input.getNodeTemplatePolicy() == null
                ? resolveNodeTemplatePolicy(decision, contextPackage)
                : input.getNodeTemplatePolicy();

        /**
         * 最后将所有补齐后的上下文字段重新组装成标准 RoundPipelineRequest，交给轮次流水线执行。
         */
        return RoundPipelineRequest.builder()
                .sessionId(sessionId)
                .userInput(userInput)
                .decision(decision)
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .nodeWorksetResult(nodeWorksetResult)
                .toolSemanticResult(input.getToolSemanticResult())
                .workingMemorySnippets(workingMemorySnippets)
                .runtimeMemorySnippets(runtimeMemorySnippets)
                .retrievedMemorySnippets(retrievedMemorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .preferenceSnippets(preferenceSnippets)
                .longTermMemorySnippets(longTermMemorySnippets)
                .executionCandidates(executionCandidates)
                .mcpResourceHints(mcpResourceHints)
                .nodeTemplatePolicy(nodeTemplatePolicy)
                .toolContext(safe(input.getToolContext()))
                .stage(firstNonBlank(input.getStage(), firstNonBlank(request.getTriggerSource(), "ROUND")))
                .repairSeed(firstNonBlank(input.getRepairSeed(), userInput))
                .runMainModel(input.isRunMainModel())
                .assistantReplyOverride(safe(input.getAssistantReplyOverride()))
                .preAssemblyTriggerSource(firstNonBlank(input.getPreAssemblyTriggerSource(), "PRE_ASSEMBLY_INPUT"))
                .postSummaryTriggerSource(firstNonBlank(input.getPostSummaryTriggerSource(), firstNonBlank(request.getTriggerSource(), "ROUND")))
                .replaceHistoryWithSummary(input.isReplaceHistoryWithSummary())
                .writeRoundState(input.isWriteRoundState())
                .latestSnapshotId(safe(input.getLatestSnapshotId()))
                .latestToolRawRef(safe(input.getLatestToolRawRef()))
                .latestToolHistoryRefs(input.getLatestToolHistoryRefs() == null ? List.of() : input.getLatestToolHistoryRefs())
                .rawToolResultChannel(input.getRawToolResultChannel() == null ? Map.of() : input.getRawToolResultChannel())
                .retrievalPlanOverrides(input.getRetrievalPlanOverrides() == null ? Map.of() : input.getRetrievalPlanOverrides())
                .build();
    }

    private void auditHook(String traceId,
                           String sessionId,
                           Long planId,
                           Long nodeId,
                           String hookName,
                           String triggerSource,
                           RoundPipelineRequest request,
                           Map<String, Object> payload) {
        /**
         * 每个状态驱动钩子都同时写审计记录和状态迁移日志，便于从两个维度回放流程。
         */
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "STATE_DRIVEN_CONTEXT_PIPELINE_HOOK",
                hookName + " hook executed",
                toJsonSafe(Map.of(
                        "hook", hookName,
                        "triggerSource", triggerSource,
                        "payload", payload
                ))
        );
        stateTransitionTraceLogger.log(
                traceId,
                sessionId,
                planId,
                nodeId,
                request == null || request.getDecision() == null || request.getDecision().getTaskState() == null
                        ? ""
                        : request.getDecision().getTaskState().name(),
                request == null || request.getDecision() == null || request.getDecision().getTaskState() == null
                        ? ""
                        : request.getDecision().getTaskState().name(),
                triggerSource,
                hookName,
                request == null ? "" : safe(request.getLatestSnapshotId()),
                request == null || request.getContextPackage() == null || request.getContextPackage().getRecoveryState() == null
                        ? ""
                        : safe(request.getContextPackage().getRecoveryState().getRecoveryEvent())
        );
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

    private List<String> extractTaskKnowledgeSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("knowledge");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "title: " + safe(stringValue(item.get("title"))) + "\ncontent: " + safe(stringValue(item.get("chunk_text"))))
                .toList();
    }

    private List<String> extractTaskLongTermSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        List<String> snippets = new ArrayList<>();
        Object factsRaw = contextPackage.getTaskContext().get("task_facts");
        if (factsRaw instanceof List<?> facts) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) facts;
            snippets.addAll(rows.stream()
                    .map(item -> "task_fact: " + safe(stringValue(item.get("fact_key"))) + "=" + safe(stringValue(item.get("fact_value_text"))))
                    .toList());
        }
        Object episodesRaw = contextPackage.getTaskContext().get("task_episodes");
        if (episodesRaw instanceof List<?> episodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) episodes;
            snippets.addAll(rows.stream()
                    .map(item -> "task_episode: " + safe(stringValue(item.get("episode_type"))) + " | " + safe(stringValue(item.get("trajectory_summary"))))
                    .toList());
        }
        return snippets;
    }

    private List<String> extractWorkingMemorySnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("working_memory");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        out.add("working.goal_raw: " + safe(stringValue(map.get("goal_raw"))));
        out.add("working.goal_refined: " + safe(stringValue(map.get("goal_refined"))));
        out.add("working.unresolved_questions: " + safe(stringValue(map.get("unresolved_questions_json"))));
        out.add("working.risks: " + safe(stringValue(map.get("risks_json"))));
        return out;
    }

    private List<String> extractRelationalPreferenceSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRelationalContext() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRelationalContext().get("semantic_facts");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "relation_pref: " + safe(stringValue(item.get("fact_key"))) + "=" + safe(stringValue(item.get("fact_value_text"))))
                .toList();
    }

    private List<String> extractRuntimeMessageSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRuntime().get("recent_messages");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> safe(stringValue(item.get("role"))) + ": " + safe(stringValue(item.get("content_text"))))
                .toList();
    }

    private List<String> mergeDistinct(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    private boolean nonEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    private boolean isReconstructionReady(InputReconstructionResult reconstructionResult) {
        return reconstructionResult != null && reconstructionResult.getExplicitTaskGoal() != null
                && !reconstructionResult.getExplicitTaskGoal().isBlank();
    }

    private Long contextPlanId(RoundPipelineRequest request) {
        return contextPlanId(request == null ? null : request.getContextPackage());
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getRuntime() == null) {
                if (contextPackage == null || contextPackage.getTaskStateEntity() == null) {
                    return null;
                }
                return toLong(contextPackage.getTaskStateEntity().getTaskId());
            }
            Object session = contextPackage.getRuntime().get("session");
            if (session instanceof Map<?, ?> row) {
                Long runtimePlan = toLong(row.get("current_plan_id"));
                if (runtimePlan != null) {
                    return runtimePlan;
                }
            }
            return contextPackage.getTaskStateEntity() == null ? null : toLong(contextPackage.getTaskStateEntity().getTaskId());
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextNodeId(RoundPipelineRequest request) {
        return contextNodeId(request == null ? null : request.getContextPackage());
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getTaskContext() == null) {
                if (contextPackage == null || contextPackage.getTaskStateEntity() == null) {
                    return null;
                }
                return toLong(contextPackage.getTaskStateEntity().getCurrentNode());
            }
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> row) {
                Long runtimeNode = toLong(row.get("active_node_id"));
                if (runtimeNode != null) {
                    return runtimeNode;
                }
            }
            return contextPackage.getTaskStateEntity() == null ? null : toLong(contextPackage.getTaskStateEntity().getCurrentNode());
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long toLong(Object value) {
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private TaskOrchestratorService taskOrchestratorService() {
        return taskOrchestratorServiceProvider.getObject();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
