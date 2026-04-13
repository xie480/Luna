package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.service.RoundPipelineOrchestrator;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.MainModelExecutionRequest;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.RoundStateWriteRequest;
import org.yilena.luna.service.model.RoundToolSemanticRequest;
import org.yilena.luna.service.model.SummaryOrchestrationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 轮次流水线编排服务实现，负责串联工具语义解析、主模型执行、摘要生成和轮次状态写回。
 */
public class RoundPipelineOrchestratorImpl implements RoundPipelineOrchestrator {

    private final ObjectProvider<TaskOrchestratorService> taskOrchestratorServiceProvider;
    private final ToolSemanticAgent toolSemanticAgent;
    private final ToolSemanticResultValidator toolSemanticResultValidator;
    private final ToolSemanticTraceLogger toolSemanticTraceLogger;
    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;
    private final StateTransitionTraceLogger stateTransitionTraceLogger;

    @Override
    public ToolSemanticResult resolveToolSemantic(RoundToolSemanticRequest request) {
        /**
         * 先做空请求兜底，避免工具语义链路缺少上下文时直接打断整轮执行。
         */
        if (request == null) {
            return fallbackToolSemanticResult("agent_tool_chain", "", "", "round_tool_semantic_request_missing");
        }
        /**
         * 从请求和上下文包中提取计划、节点、工具描述和任务态，作为语义翻译的最小输入集合。
         */
        StructuredContextPackage contextPackage = request.getContextPackage();
        Long planId = contextPlanId(contextPackage);
        Long nodeId = contextNodeId(contextPackage);
        String toolName = firstNonBlank(request.getToolName(), resolvePrimaryToolName(request.getExecutionCandidates()));
        String toolDescription = firstNonBlank(request.getToolDescription(), resolvePrimaryToolDescription(request.getExecutionCandidates()));
        String explicitGoal = nullSafe(request.getExplicitTaskGoal());
        TaskRuntimeState taskState = request.getTaskState() == null
                ? (contextPackage == null ? null : contextPackage.getTaskState())
                : request.getTaskState();
        String stage = nullSafe(request.getStage());
        ToolSemanticResult translated;
        try {
            /**
             * 调用工具语义代理把原始工具结果翻译成结构化业务语义，供主模型和审计链路复用。
             */
            translated = toolSemanticAgent.translate(
                    toolName,
                    toolDescription,
                    nullSafe(request.getToolContext()),
                    taskState,
                    explicitGoal
            );
        } catch (Exception ex) {
            translated = fallbackToolSemanticResult(
                    toolName,
                    toolDescription,
                    request.getToolContext(),
                    ex.getMessage()
            );
        }
        if (translated == null || Boolean.TRUE.equals(safeMap(translated.getSemanticPayload()).get("semantic_translation_failed"))) {
            Object failureReason = translated == null ? null : safeMap(translated.getSemanticPayload()).get("failure_reason");
            translated = fallbackToolSemanticResult(
                    toolName,
                    toolDescription,
                    request.getToolContext(),
                    translated == null ? "tool_semantic_translation_empty" : (failureReason == null ? "" : String.valueOf(failureReason))
            );
        }

        /**
         * 对语义结果做校验、归一化和审计记录，确保后续链路读取到的是可用结构。
         */
        translated = validateAndTraceToolSemantic(
                nullSafe(request.getSessionId()),
                planId,
                nodeId,
                stage,
                translated,
                contextPackage,
                safeMap(request.getRawToolResultChannel())
        );
        return translated;
    }

    @Override
    public RoundPipelineResult executeRound(RoundPipelineRequest request) {
        // 第一步：请求参数校验
        // 如果轮次请求为空则直接阻断，避免继续执行导致状态写回和审计记录失真
        if (request == null) {
            return RoundPipelineResult.builder()
                    .blocked(true)
                    .blockedReason("round_request_missing")
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

        // 第二步：构建轮次执行上下文并提取工作集
        // 从请求中整理上下文包、候选能力、MCP提示和知识证据，形成当前轮次执行所需的统一工作集
        String sessionId = nullSafe(request.getSessionId());
        StructuredContextPackage contextPackage = request.getContextPackage();
        Long planId = contextPlanId(contextPackage);
        Long nodeId = contextNodeId(contextPackage);
        String traceId = "round_pipeline:" + sessionId + ":" + System.currentTimeMillis();
        NodeWorksetResult nodeWorksetResult = request.getNodeWorksetResult();
        ContextRerankResult rerankResult = nodeWorksetResult == null ? null : nodeWorksetResult.getRerankResult();

        // 提取执行候选项：优先使用请求中的值，缺失时回退到节点工作集中的值
        List<Resource> executionCandidates = request.getExecutionCandidates() == null
                ? (nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null ? List.of() : nodeWorksetResult.getExecutionCandidates())
                : request.getExecutionCandidates();

        // 提取MCP资源提示：优先使用请求中的值，缺失时回退到节点工作集中的值
        List<String> mcpResourceHints = request.getMcpResourceHints() == null
                ? (nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null ? List.of() : nodeWorksetResult.getMcpResourceHints())
                : request.getMcpResourceHints();

        // 提取知识证据块：从节点工作集中获取已选中的知识证据
        List<EvidenceBlock> knowledgeEvidenceBlocks = nodeWorksetResult == null || nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks() == null
                ? List.of()
                : nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks();

        // 第三步：解析工具语义
        // 如果上游尚未提供工具语义结果，则在轮次内部补做一次语义解析，确保主模型有足够的工具总结信息
        ToolSemanticResult effectiveToolSemantic = request.getToolSemanticResult();
        if (effectiveToolSemantic == null) {
            effectiveToolSemantic = resolveToolSemantic(RoundToolSemanticRequest.builder()
                    .sessionId(sessionId)
                    .contextPackage(contextPackage)
                    .taskState(request.getDecision() == null ? null : request.getDecision().getTaskState())
                    .explicitTaskGoal(request.getReconstructionResult() == null ? null : request.getReconstructionResult().getExplicitTaskGoal())
                    .executionCandidates(executionCandidates)
                    .toolContext(request.getToolContext())
                    .stage(firstNonBlank(request.getStage(), "ROUND"))
                    .rawToolResultChannel(safeMap(request.getRawToolResultChannel()))
                    .build());
        }

        // 第四步：初始化结果变量
        SummaryResult preAssemblySummary = null;
        MainModelOrchestrationResult modelResult = null;
        String assistantReply = nullSafe(request.getAssistantReplyOverride());
        String finalSnapshotId = nullSafe(request.getLatestSnapshotId());

        // 第五步：主模型执行流程（条件执行）
        // 仅当请求明确要求运行主模型时才执行以下步骤
        if (request.isRunMainModel()) {
            // 5.1 执行预摘要：在主模型执行前将当前轮次上下文压缩成更利于生成的输入片段
            SummaryOrchestrationResult preSummaryResult = taskOrchestratorService().orchestrateSummary(
                    sessionId,
                    nullSafe(request.getUserInput()),
                    "",
                    contextPackage,
                    knowledgeEvidenceBlocks,
                    mcpResourceHints,
                    effectiveToolSemantic,
                    false,
                    firstNonBlank(request.getPreAssemblyTriggerSource(), "ROUND_PRE_ASSEMBLY")
            );
            preAssemblySummary = preSummaryResult == null ? null : preSummaryResult.getSummaryResult();

            // 5.2 触发主模型编排：基于完整工作集调用主模型生成本轮回复
            modelResult = taskOrchestratorService().orchestrateMainModel(
                    MainModelExecutionRequest.builder()
                            .sessionId(sessionId)
                            .userInput(nullSafe(request.getUserInput()))
                            .contextPackage(contextPackage)
                            .reconstructionResult(request.getReconstructionResult())
                            .rerankResult(rerankResult)
                            .toolSemanticResult(effectiveToolSemantic)
                            .knowledgeEvidenceBlocks(knowledgeEvidenceBlocks)
                            .workingMemorySnippets(safeList(request.getWorkingMemorySnippets()))
                            .runtimeMemorySnippets(safeList(request.getRuntimeMemorySnippets()))
                            .retrievedMemorySnippets(safeList(request.getRetrievedMemorySnippets()))
                            .knowledgeSnippets(safeList(request.getKnowledgeSnippets()))
                            .preferenceSnippets(safeList(request.getPreferenceSnippets()))
                            .longTermMemorySnippets(safeList(request.getLongTermMemorySnippets()))
                            .executionCandidates(executionCandidates)
                            .mcpResourceHints(mcpResourceHints)
                            .toolContext(nullSafe(request.getToolContext()))
                            .nodeTemplatePolicy(request.getNodeTemplatePolicy())
                            .roundSummaryInput(preAssemblySummary)
                            .planId(contextPlanId(contextPackage))
                            .nodeId(contextNodeId(contextPackage))
                            .stage(firstNonBlank(request.getStage(), "ROUND"))
                            .repairSeed(firstNonBlank(request.getRepairSeed(), request.getUserInput()))
                            .rawToolResultChannel(safeMap(request.getRawToolResultChannel()))
                            .build()
            );

            // 5.3 主模型执行结果校验
            // 如果主模型执行被阻断，则记录状态迁移并尽早返回，防止后续摘要和写回覆盖真实阻断原因
            if (modelResult == null || modelResult.isBlocked()) {
                stateTransitionTraceLogger.log(
                        traceId,
                        sessionId,
                        planId,
                        nodeId,
                        request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                        request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                        firstNonBlank(request.getStage(), "ROUND"),
                        "execute",
                        modelResult == null ? "" : firstNonBlank(modelResult.getFinalSnapshotId(), ""),
                        contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
                );
                return RoundPipelineResult.builder()
                        .blocked(true)
                        .blockedReason(modelResult == null ? "main_model_result_missing" : nullSafe(modelResult.getBlockedReason()))
                        .toolSemanticResult(effectiveToolSemantic)
                        .preAssemblySummary(preAssemblySummary)
                        .mainModelResult(modelResult)
                        .summaryResult(null)
                        .finalSnapshotId(modelResult == null ? finalSnapshotId : firstNonBlank(modelResult.getFinalSnapshotId(), finalSnapshotId))
                        .decision(request.getDecision())
                        .contextPackage(contextPackage)
                        .reconstructionResult(request.getReconstructionResult())
                        .nodeWorksetResult(nodeWorksetResult)
                        .build();
            }

            // 5.4 更新最终结果：从主模型输出中提取回复文本和快照ID
            assistantReply = nullSafe(modelResult.getReplyText());
            finalSnapshotId = firstNonBlank(modelResult.getFinalSnapshotId(), finalSnapshotId);
        }

        // 第六步：生成轮次后摘要
        // 在主模型输出后生成本轮最终摘要，用于历史替换、状态写回和后续轮次的上下文压缩
        SummaryOrchestrationResult postSummary = taskOrchestratorService().orchestrateSummary(
                sessionId,
                nullSafe(request.getUserInput()),
                assistantReply,
                contextPackage,
                knowledgeEvidenceBlocks,
                mcpResourceHints,
                effectiveToolSemantic,
                request.isReplaceHistoryWithSummary(),
                firstNonBlank(request.getPostSummaryTriggerSource(), "ROUND")
        );
        SummaryResult summaryResult = postSummary == null ? null : postSummary.getSummaryResult();

        // 第七步：状态写回（条件执行）
        // 当轮次需要持久化状态时，统一写回决策、摘要、工具原始结果引用和检索计划信息
        if (request.isWriteRoundState()) {
            taskOrchestratorService().writeRoundState(RoundStateWriteRequest.builder()
                    .sessionId(sessionId)
                    .decision(request.getDecision())
                    .contextPackage(contextPackage)
                    .reconstruction(request.getReconstructionResult())
                    .rerankResult(rerankResult)
                    .toolSemanticResult(effectiveToolSemantic)
                    .summaryResult(summaryResult)
                    .latestSnapshotId(finalSnapshotId)
                    .latestToolRawRef(nullSafe(request.getLatestToolRawRef()))
                    .latestToolHistoryRefs(safeList(request.getLatestToolHistoryRefs()))
                    .rawToolResultChannel(safeMap(request.getRawToolResultChannel()))
                    .ragQuery(nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getRagQuery()))
                    .memoryQuery(nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getMemoryQuery()))
                    .mcpQuery(nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getMcpDrivenInput()))
                    .retrievalPlanOverrides(safeMap(request.getRetrievalPlanOverrides()))
                    .build());
        }

        // 第八步：记录状态迁移日志
        // 轮次正常收尾时记录一次 writeback 迁移日志，为后续排查上下文演进提供链路证据
        stateTransitionTraceLogger.log(
                traceId,
                sessionId,
                planId,
                nodeId,
                request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                request.getDecision() == null || request.getDecision().getTaskState() == null ? "" : request.getDecision().getTaskState().name(),
                firstNonBlank(request.getStage(), "ROUND"),
                "writeback",
                finalSnapshotId,
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 第九步：构建并返回最终结果
        // 返回完整的轮次执行结果，包含所有阶段的产出物
        return RoundPipelineResult.builder()
                .blocked(false)
                .blockedReason("")
                .toolSemanticResult(effectiveToolSemantic)
                .preAssemblySummary(preAssemblySummary)
                .mainModelResult(modelResult)
                .summaryResult(summaryResult)
                .finalSnapshotId(finalSnapshotId)
                .decision(request.getDecision())
                .contextPackage(contextPackage)
                .reconstructionResult(request.getReconstructionResult())
                .nodeWorksetResult(nodeWorksetResult)
                .build();
    }


    private ToolSemanticResult validateAndTraceToolSemantic(String sessionId,
                                                            Long planId,
                                                            Long nodeId,
                                                            String stage,
                                                            ToolSemanticResult translated,
                                                            StructuredContextPackage contextPackage,
                                                            Map<String, Object> rawToolResultChannel) {
        /**
         * 先为缺失结果补回退语义对象，再做结构校验，避免下游消费 null 导致整轮失败。
         */
        ToolSemanticResult safeTranslated = translated == null
                ? fallbackToolSemanticResult("agent_tool_chain", "", "", "tool_semantic_translation_empty")
                : translated;
        ToolSemanticResultValidator.ValidationResult validationResult = toolSemanticResultValidator.validate(safeTranslated, contextPackage);
        if (validationResult.valid()) {
            /**
             * 校验通过时记录成功审计，便于回溯语义链路的稳定性。
             */
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_VALIDATION",
                    firstNonBlank(stage, "ROUND") + " semantic validation passed",
                    "{}"
            );
        } else {
            /**
             * 校验失败时记录问题明细，并在 schema 非法场景下额外打点，便于专项排查。
             */
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("issues", validationResult.issues());
            payload.put("stage", nullSafe(stage));
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_VALIDATION",
                    firstNonBlank(stage, "ROUND") + " semantic validation failed",
                    toJsonSafe(payload)
            );
            if (validationResult.issues() != null && validationResult.issues().contains("schema_invalid")) {
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        planId,
                        nodeId,
                        "TOOL_SEMANTIC_SCHEMA_INVALID",
                        "semantic result rejected by schema, normalized fallback applied",
                        toJsonSafe(payload)
                );
            }
        }
        if (validationResult.normalized() != null) {
            safeTranslated = validationResult.normalized();
        }

        /**
         * 最后将语义结果、原始结果引用和校验问题统一落审计轨迹，供运行时回放和诊断。
         */
        String rawResultRef = resolveLatestRawResultRef(rawToolResultChannel, null);
        List<String> validationIssues = validationResult.issues() == null ? List.of() : validationResult.issues();
        String semanticTraceId = UUID.randomUUID().toString();
        Map<String, Object> semanticPipelinePayload = new LinkedHashMap<>();
        semanticPipelinePayload.put("traceId", semanticTraceId);
        semanticPipelinePayload.put("rawResultRef", rawResultRef);
        semanticPipelinePayload.put("rawDigest", nullSafe(safeTranslated.getRawResultDigest()));
        semanticPipelinePayload.put("semanticResult", safeTranslated);
        semanticPipelinePayload.put("validationIssues", validationIssues);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "TOOL_SEMANTIC_PIPELINE_TRACE",
                firstNonBlank(stage, "ROUND") + " semantic pipeline traced",
                toJsonSafe(semanticPipelinePayload)
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "TOOL_SEMANTIC_TRANSLATION",
                firstNonBlank(stage, "ROUND") + " tool semantic translated",
                toJsonSafe(safeTranslated)
        );
        toolSemanticTraceLogger.log(sessionId, planId, nodeId, safeTranslated);
        return safeTranslated;
    }

    private TaskOrchestratorService taskOrchestratorService() {
        return taskOrchestratorServiceProvider.getObject();
    }

    @SuppressWarnings("unchecked")
    private String resolveLatestRawResultRef(Map<String, Object> rawToolResultChannel, String fallbackRef) {
        if (rawToolResultChannel != null && !rawToolResultChannel.isEmpty()) {
            Object latest = rawToolResultChannel.get("latestToolRawRef");
            if (latest != null && !String.valueOf(latest).isBlank()) {
                return String.valueOf(latest);
            }
            Object refs = rawToolResultChannel.get("toolHistoryRefs");
            if (refs instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first != null && !String.valueOf(first).isBlank()) {
                    return String.valueOf(first);
                }
            }
        }
        if (fallbackRef != null && !fallbackRef.isBlank()) {
            return fallbackRef;
        }
        return "tool_execution_trace:latest";
    }

    private ToolSemanticResult fallbackToolSemanticResult(String toolName,
                                                          String toolDescription,
                                                          String rawToolResult,
                                                          String errorMessage) {
        return ToolSemanticResult.builder()
                .toolName(firstNonBlank(toolName, "agent_tool_chain"))
                .toolDescription(nullSafe(toolDescription))
                .rawResultDigest(truncate(rawToolResult, 640))
                .toolStatus("UNKNOWN")
                .keyFacts(List.of("semantic_translation_failed"))
                .businessImpact("semantic_translation_unavailable_raw_channel_only")
                .unresolvedIssues(errorMessage == null || errorMessage.isBlank()
                        ? List.of("semantic_translation_failed")
                        : List.of(truncate(errorMessage, 200)))
                .nextStepHint("retry_or_recover")
                .confidence(0.0)
                .semanticPayload(Map.of(
                        "status", "UNKNOWN",
                        "tool", firstNonBlank(toolName, ""),
                        "raw_channel_only", true,
                        "semantic_translation_failed", true,
                        "failure_reason", errorMessage == null ? "" : truncate(errorMessage, 200)
                ))
                .build();
    }

    private String resolvePrimaryToolName(List<Resource> executionCandidates) {
        if (executionCandidates == null || executionCandidates.isEmpty()) {
            return "agent_tool_chain";
        }
        Resource first = executionCandidates.get(0);
        return first == null || first.getName() == null || first.getName().isBlank()
                ? "agent_tool_chain"
                : first.getName();
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
                + ", server=" + firstNonBlank(first.getServerCode(), "local")
                + ", name=" + firstNonBlank(first.getName(), "");
    }

    private List<String> safeList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String text, int maxLen) {
        String normalized = nullSafe(text);
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen));
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
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignore) {
            return null;
        }
    }
}
