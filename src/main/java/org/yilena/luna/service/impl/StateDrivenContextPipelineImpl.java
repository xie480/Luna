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
        // 第一步：请求参数校验
        // 检查状态驱动请求对象及其内部的轮次请求是否为空，若缺失则直接返回阻断结果
        if (request == null || request.getRoundPipelineRequest() == null) {
            return blocked("state_driven_context_pipeline_request_missing");
        }

        // 第二步：水化轮次请求（Hydration）
        // 对原始轮次请求进行数据补齐操作，填充缺失的决策信息、上下文包、输入重构结果和节点工作集
        // 将不完整的请求转换为真正可执行的完整请求对象
        RoundPipelineRequest hydratedRoundRequest = hydrateRoundRequest(request);
        if (hydratedRoundRequest == null) {
            return blocked("state_driven_context_pipeline_hydration_failed");
        }

        // 第三步：构建审计追踪上下文
        // 提取会话ID、触发源、计划ID、节点ID等关键标识，生成唯一的追踪ID
        // 用于后续各阶段的审计日志记录，保留状态驱动链路的完整执行轨迹
        String sessionId = firstNonBlank(request.getSessionId(), hydratedRoundRequest.getSessionId());
        String triggerSource = firstNonBlank(request.getTriggerSource(), "STATE_DRIVEN_CONTEXT_PIPELINE");
        Long planId = contextPlanId(hydratedRoundRequest);
        Long nodeId = contextNodeId(hydratedRoundRequest);
        String traceId = "state_pipeline:" + sessionId + ":" + System.currentTimeMillis();

        // 第四步：执行前审计钩子 - 记录四个关键准备阶段的状态
        // 4.1 重构阶段（reconstruct）：记录输入重构结果和当前阶段信息
        auditHook(traceId, sessionId, planId, nodeId, "reconstruct", triggerSource, hydratedRoundRequest, Map.of(
                "hasReconstruction", hydratedRoundRequest.getReconstructionResult() != null,
                "stage", safe(hydratedRoundRequest.getStage())
        ));

        // 4.2 召回阶段（recall）：记录节点工作集、执行候选项和MCP资源提示的可用性
        auditHook(traceId, sessionId, planId, nodeId, "recall", triggerSource, hydratedRoundRequest, Map.of(
                "hasNodeWorkset", hydratedRoundRequest.getNodeWorksetResult() != null,
                "hasExecutionCandidates", hydratedRoundRequest.getExecutionCandidates() != null && !hydratedRoundRequest.getExecutionCandidates().isEmpty(),
                "hasMcpHints", hydratedRoundRequest.getMcpResourceHints() != null && !hydratedRoundRequest.getMcpResourceHints().isEmpty()
        ));

        // 4.3 重排阶段（rerank）：记录节点工作集中的重排序结果是否就绪
        auditHook(traceId, sessionId, planId, nodeId, "rerank", triggerSource, hydratedRoundRequest, Map.of(
                "hasRerankResult", hydratedRoundRequest.getNodeWorksetResult() != null
                        && hydratedRoundRequest.getNodeWorksetResult().getRerankResult() != null
        ));

        // 4.4 组装阶段（assemble）：记录是否运行主模型以及是否写入轮次状态
        auditHook(traceId, sessionId, planId, nodeId, "assemble", triggerSource, hydratedRoundRequest, Map.of(
                "runMainModel", hydratedRoundRequest.isRunMainModel(),
                "writeRoundState", hydratedRoundRequest.isWriteRoundState()
        ));

        // 第五步：执行轮次流水线主流程
        // 调用轮次编排器执行核心的上下文处理逻辑，包括工具语义分析、主模型推理等
        RoundPipelineResult result = roundPipelineOrchestrator.executeRound(hydratedRoundRequest);

        // 第六步：执行后审计钩子 - 记录执行和回写阶段的结果
        // 6.1 执行阶段（execute）：记录是否被阻断及阻断原因
        auditHook(traceId, sessionId, planId, nodeId, "execute", triggerSource, hydratedRoundRequest, Map.of(
                "blocked", result != null && result.isBlocked(),
                "blockedReason", result == null ? "round_result_missing" : safe(result.getBlockedReason())
        ));

        // 6.2 回写阶段（writeback）：记录最终快照ID和摘要结果的可用性
        auditHook(traceId, sessionId, planId, nodeId, "writeback", triggerSource, hydratedRoundRequest, Map.of(
                "finalSnapshotId", result == null ? "" : safe(result.getFinalSnapshotId()),
                "summaryPresent", result != null && result.getSummaryResult() != null
        ));

        // 第七步：执行结果校验
        // 如果轮次执行结果为空，返回阻断状态
        if (result == null) {
            return blocked("round_result_missing");
        }

        // 第八步：构建并返回最终结果（带字段兜底策略）
        // 优先使用轮次执行返回的结果，当某些字段为空时回退到水化请求中的对应值
        // 确保返回结果的完整性，避免因部分字段缺失导致下游处理失败
        return RoundPipelineResult.builder()
                .blocked(result.isBlocked())
                .blockedReason(result.getBlockedReason())
                .toolSemanticResult(result.getToolSemanticResult())
                .preAssemblySummary(result.getPreAssemblySummary())
                .mainModelResult(result.getMainModelResult())
                .summaryResult(result.getSummaryResult())
                .finalSnapshotId(result.getFinalSnapshotId())
                // 决策信息兜底：执行结果为空时使用水化请求中的决策
                .decision(result.getDecision() == null ? hydratedRoundRequest.getDecision() : result.getDecision())
                // 上下文包兜底：执行结果为空时使用水化请求中的上下文
                .contextPackage(result.getContextPackage() == null ? hydratedRoundRequest.getContextPackage() : result.getContextPackage())
                // 重构结果兜底：执行结果为空时使用水化请求中的重构结果
                .reconstructionResult(result.getReconstructionResult() == null ? hydratedRoundRequest.getReconstructionResult() : result.getReconstructionResult())
                // 节点工作集兜底：执行结果为空时使用水化请求中的节点工作集
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
        // 第一步：提取原始轮次请求并校验
        // 从状态驱动请求中获取内部的轮次请求对象，如果为空则直接返回null表示水化失败
        RoundPipelineRequest input = request.getRoundPipelineRequest();
        if (input == null) {
            return null;
        }

        // 第二步：整合基础会话信息
        // 优先使用外层请求的会话ID，缺失时回退到内层轮次请求的会话ID
        String sessionId = firstNonBlank(request.getSessionId(), input.getSessionId());
        String userInput = safe(input.getUserInput());

        // 第三步：读取已有的核心上下文组件
        // 尝试从输入中获取决策、上下文包和重构结果，这些是轮次执行的基础依赖
        OrchestrationDecision decision = input.getDecision();
        StructuredContextPackage contextPackage = input.getContextPackage();
        InputReconstructionResult reconstructionResult = input.getReconstructionResult();

        // 第四步：核心上下文缺失时的自动补齐策略
        // 当决策、上下文包或重构结果任一缺失且用户输入非空时，调用任务编排服务重新执行用户输入编排
        if ((decision == null || contextPackage == null || reconstructionResult == null) && !userInput.isBlank()) {
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

        // 第五步：校验重构结果就绪状态
        // 检查是否具备明确的任务目标，这是后续节点工作集生成和主模型执行的必要前提
        if (!isReconstructionReady(reconstructionResult)) {
            // 记录阻断原因到审计服务，区分是完全缺少重构结果还是缺少明确任务目标
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

        // 第六步：节点工作集生成（条件触发）
        // 当节点工作集尚未生成且所有前置条件满足时，基于当前决策和上下文补做节点级检索与候选能力编排
        NodeWorksetResult nodeWorksetResult = input.getNodeWorksetResult();
        if (nodeWorksetResult == null
                && !userInput.isBlank()
                && decision != null
                && contextPackage != null
                && reconstructionResult != null) {
            nodeWorksetResult = taskOrchestratorService().orchestrateNodeWorkset(
                    sessionId,
                    userInput,
                    decision,
                    contextPackage,
                    reconstructionResult
            );
        }

        // 第七步：提取执行候选项和MCP资源提示
        // 优先级策略：请求中的值 > 节点工作集中的值 > 空列表
        List<Resource> executionCandidates = nonEmpty(input.getExecutionCandidates())
                ? input.getExecutionCandidates()
                : (nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null ? List.of() : nodeWorksetResult.getExecutionCandidates());
        List<String> mcpResourceHints = nonEmpty(input.getMcpResourceHints())
                ? input.getMcpResourceHints()
                : (nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null ? List.of() : nodeWorksetResult.getMcpResourceHints());

        // 第八步：知识片段合并策略
        // 按优先级依次尝试：请求中的知识片段 > 节点工作集中选中的知识片段 > 从上下文包中提取的任务知识片段
        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        if (nodeWorksetResult != null && nonEmpty(nodeWorksetResult.getSelectedKnowledgeSnippets())) {
            knowledgeSnippets = nodeWorksetResult.getSelectedKnowledgeSnippets();
        }
        if (nonEmpty(input.getKnowledgeSnippets())) {
            knowledgeSnippets = input.getKnowledgeSnippets();
        }

        // 第九步：偏好片段合并策略
        // 合并关系型偏好片段和节点工作集中选中的偏好片段，去重后作为基础值
        // 如果请求中提供了偏好片段，则直接使用请求中的值覆盖
        List<String> preferenceSnippets = mergeDistinct(
                extractRelationalPreferenceSnippets(contextPackage),
                nodeWorksetResult == null ? List.of() : nodeWorksetResult.getSelectedPreferenceSnippets()
        );
        if (nonEmpty(input.getPreferenceSnippets())) {
            preferenceSnippets = input.getPreferenceSnippets();
        }

        // 第十步：各类记忆片段的提取与兜底
        // 长短期记忆、工作记忆、运行时记忆和检索记忆的优先级策略均为：请求值优先，缺失时从上下文包中提取

        // 长期记忆片段：优先使用请求中的值，缺失时从上下文包中提取任务相关的长期记忆
        List<String> longTermMemorySnippets = nonEmpty(input.getLongTermMemorySnippets())
                ? input.getLongTermMemorySnippets()
                : extractTaskLongTermSnippets(contextPackage);

        // 工作记忆片段：优先使用请求中的值，缺失时从上下文包中提取工作记忆
        List<String> workingMemorySnippets = nonEmpty(input.getWorkingMemorySnippets())
                ? input.getWorkingMemorySnippets()
                : extractWorkingMemorySnippets(contextPackage);

        // 运行时记忆片段：优先使用请求中的值，缺失时从上下文包中提取运行时消息片段
        List<String> runtimeMemorySnippets = nonEmpty(input.getRuntimeMemorySnippets())
                ? input.getRuntimeMemorySnippets()
                : extractRuntimeMessageSnippets(contextPackage);

        // 检索记忆片段：优先使用请求中的值，缺失时使用节点工作集中选中的记忆片段
        List<String> retrievedMemorySnippets = nonEmpty(input.getRetrievedMemorySnippets())
                ? input.getRetrievedMemorySnippets()
                : (nodeWorksetResult == null || nodeWorksetResult.getSelectedMemorySnippets() == null ? List.of() : nodeWorksetResult.getSelectedMemorySnippets());

        // 第十一步：解析节点模板策略
        // 如果请求中未指定节点模板策略，则基于决策和上下文包自动推导合适的策略
        ContextNodeTemplatePolicy nodeTemplatePolicy = input.getNodeTemplatePolicy() == null
                ? resolveNodeTemplatePolicy(decision, contextPackage)
                : input.getNodeTemplatePolicy();

        // 第十二步：组装完整的水化请求对象
        // 将所有补齐后的上下文字段重新组装成标准的 RoundPipelineRequest，交给轮次流水线执行
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
