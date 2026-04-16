package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.ModelHintConstant;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.StateTransitionTraceLogger;
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.EvidenceBlockBuilder;
import org.yilena.luna.context.GlobalContextRerankAgent;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.MemoryQueryBuilder;
import org.yilena.luna.context.McpCandidatePreRank;
import org.yilena.luna.context.McpQueryBuilder;
import org.yilena.luna.context.McpResourceHintExtractor;
import org.yilena.luna.context.RagQueryBuilder;
import org.yilena.luna.context.RecoveryContextAgent;
import org.yilena.luna.context.RerankTraceLogger;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.SummaryStateSnapshotValidator;
import org.yilena.luna.context.SummaryTraceLogger;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.memory.model.GovernedSignal;
import org.yilena.luna.memory.support.ToolRawRefResolver;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.PromptSnapshotBridgeService;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;
import org.yilena.luna.prompt.governance.support.PromptKeyAliasSupport;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.rag.api.RetrievalService;
import org.yilena.luna.rag.models.ConversationMessage;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalOptions;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.model.BlueprintOrchestrationResult;
import org.yilena.luna.service.model.BlueprintDraft;
import org.yilena.luna.service.model.MainModelExecutionRequest;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundStateWriteRequest;
import org.yilena.luna.service.model.RoundToolSemanticRequest;
import org.yilena.luna.service.model.SummaryOrchestrationResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.service.model.ToolDecisionCommand;
import org.yilena.luna.service.model.ToolDecisionNodeResult;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.ContextSnapshotStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;
import org.yilena.luna.utils.ToolDecisionInputSignatureUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@RequiredArgsConstructor
/**
 * 任务编排服务实现，负责串联输入重构、召回重排、摘要生成、主模型执行与状态写回等核心上下文链路。
 */
public class TaskOrchestratorServiceImpl implements TaskOrchestratorService {

    private static final int ACTIVE_REF_MAX_PER_CHANNEL = 24;
    private static final int ACTIVE_TOOL_REF_MAX = 12;
    private static final String REF_GOVERNANCE_META_KEY = "_activeRefGovernance";
    private static final int ROUND_DECAY_ON_STAGE_CHANGE = 2;
    private static final int ROUND_DECAY_ON_STEP_ADVANCED = 1;
    private static final int KNOWLEDGE_REF_TTL = 5;
    private static final int MEMORY_REF_TTL = 8;
    private static final int TOOL_REF_TTL = 3;
    private static final int MCP_PROMPT_REF_TTL = 4;
    private static final int MCP_RESOURCE_REF_TTL = 4;
    private static final int MCP_WORKFLOW_REF_TTL = 4;
    private static final int MCP_TOOL_REF_TTL = 4;
    private static final int SUMMARY_REPLACE_HISTORY_MIN_TURNS = 8;
    private static final double RECALL_MIN_CONFIDENCE_LIGHT = 0.35d;
    private static final double RECALL_MIN_CONFIDENCE_PLANNING = 0.50d;
    private static final double RECALL_MIN_CONFIDENCE_EXECUTION = 0.60d;
    private static final int RECALL_MAX_MISSING_SLOTS_LIGHT = 5;
    private static final int RECALL_MAX_MISSING_SLOTS_PLANNING = 3;
    private static final int RECALL_MAX_MISSING_SLOTS_EXECUTION = 2;

    private final ContextCompilerService contextCompilerService;
    private final InputReconstructionAgent inputReconstructionAgent;
    private final EventIngressService eventIngressService;
    private final RecoveryContextAgent recoveryContextAgent;
    private final RuntimeAuditService runtimeAuditService;
    private final RagQueryBuilder ragQueryBuilder;
    private final MemoryQueryBuilder memoryQueryBuilder;
    private final McpQueryBuilder mcpQueryBuilder;
    private final McpCandidatePreRank mcpCandidatePreRank;
    private final McpResourceHintExtractor mcpResourceHintExtractor;
    private final GlobalContextRerankAgent globalContextRerankAgent;
    private final EvidenceBlockBuilder evidenceBlockBuilder;
    private final RetrievalService retrievalService;
    private final CapabilityPolicyRouterService capabilityPolicyRouterService;
    private final ToolRouter toolRouter;
    private final RerankTraceLogger rerankTraceLogger;
    private final RecoveryStateStore recoveryStateStore;
    private final SummaryAgent summaryAgent;
    private final SummaryStateSnapshotValidator summaryStateSnapshotValidator;
    private final SummaryTraceLogger summaryTraceLogger;
    private final ContextAssembler contextAssembler;
    private final ContextTraceLogger contextTraceLogger;
    private final ToolSemanticAgent toolSemanticAgent;
    private final ToolSemanticTraceLogger toolSemanticTraceLogger;
    private final AgentService agentService;
    private final ContextSnapshotStore contextSnapshotStore;
    private final ContextStateStore contextStateStore;
    private final TaskStateStore taskStateStore;
    private final RetrievalStateStore retrievalStateStore;
    private final ToolStateStore toolStateStore;
    private final ToolSemanticResultValidator toolSemanticResultValidator;
    private final StateTransitionTraceLogger stateTransitionTraceLogger;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private PromptSnapshotBridgeService promptSnapshotBridgeService;
    @Autowired(required = false)
    private PromptRegistryService promptRegistryService;
    @Autowired(required = false)
    private PromptResolverService promptResolverService;

    /**
     * 编排处理用户输入，驱动任务状态机完成从意图理解到上下文决策的完整流程。
     *
     * <p>该方法 orchestrates 以下核心步骤：</p>
     * <ol>
     *   <li>编译会话上下文并重构用户意图，生成治理信号</li>
     *   <li>通过事件入口服务驱动任务状态机，产出编排决策</li>
     *   <li>检测并处理恢复场景（中断恢复、异常修复等）</li>
     *   <li>持久化上下文快照和决策审计日志</li>
     * </ol>
     *
     * @param sessionId 会话ID，用于标识和追踪用户会话
     * @param userInput 用户原始输入文本，可能包含指令、问题或对话内容
     * @return TaskOrchestrationResult 编排结果，包含：
     *         - decision: 任务状态机的编排决策
     *         - contextPackage: 完整的结构化上下文包
     *         - reconstructionResult: 意图重构结果
     *         - recovered: 是否触发了恢复流程
     *         - recoveryEvent: 恢复事件类型（如果触发）
     *         - interruptReason: 中断原因说明（如果触发）
     */
    @Override
    public TaskOrchestrationResult orchestrateUserInput(String sessionId, String userInput) {
        // 生成唯一的转换追踪ID，用于全链路审计
        // 该ID贯穿整个编排流程，支持在分布式系统中追踪单次用户输入的完整处理路径
        String transitionTraceId = buildTraceId("TASK_ORCHESTRATOR", sessionId, null, null);

        // ========================================================================
        // 第一阶段：上下文编译与意图重构
        // ========================================================================
        // 目标：将用户原始输入转换为结构化的治理信号，供状态机消费

        // 1.1 编译会话上下文
        // 从数据库加载会话相关的历史状态，包括：
        // - 任务状态（TaskState）：当前任务的执行进度
        // - 关系状态（RelationalState）：用户画像和偏好
        // - 上下文状态（ContextState）：最近的对话历史和检索结果
        // - 恢复状态（RecoveryState）：是否存在待处理的恢复事件
        StructuredContextPackage preContextPackage = contextCompilerService.compile(sessionId, userInput, null, null);

        // 1.2 意图重构
        // 使用AI代理分析用户输入，识别：
        // - 显式意图：用户明确表达的需求
        // - 隐式意图：基于上下文的隐含需求
        // - 意图类型：查询、指令、对话、代码操作等
        // - 关键实体：提取的参数、文件路径、技术栈等
        InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                sessionId,
                userInput,
                preContextPackage,
                preContextPackage == null ? null : preContextPackage.getTaskState(),
                preContextPackage == null ? null : preContextPackage.getRelationalState()
        );

        // 1.3 记录意图重构阶段的转换轨迹
        // 用于审计和分析意图理解的准确性，支持后续优化重构算法
        stateTransitionTraceLogger.log(
                transitionTraceId,
                sessionId,
                contextPlanId(preContextPackage),
                contextNodeId(preContextPackage),
                preContextPackage == null || preContextPackage.getTaskState() == null ? "" : preContextPackage.getTaskState().name(),
                preContextPackage == null || preContextPackage.getTaskState() == null ? "" : preContextPackage.getTaskState().name(),
                "CHAT",
                "reconstruct",
                preContextPackage == null || preContextPackage.getContextState() == null ? "" : nullSafe(preContextPackage.getContextState().getLatestContextSnapshotId()),
                preContextPackage == null || preContextPackage.getRecoveryState() == null ? "" : nullSafe(preContextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 1.4 构建治理信号
        // 将意图重构结果封装为标准化的信号格式，包含：
        // - 意图分类标签
        // - 置信度评分
        // - 需要触发的能力列表
        // - 上下文刷新策略
        GovernedSignal governedSignal = buildGovernedSignal(userInput, reconstructionResult);

        // ========================================================================
        // 第二阶段：驱动任务状态机，产出编排决策
        // ========================================================================
        // 目标：基于治理信号和当前状态，计算下一步的执行策略

        // 2.1 调用事件入口服务
        // 状态机根据以下因素做出决策：
        // - 当前任务状态（进行中/已完成/已中断）
        // - 用户意图类型
        // - 可用能力和工具
        // - 资源约束和依赖关系
        OrchestrationDecision decision = eventIngressService.ingestUserInput(
                sessionId,
                userInput,
                toJsonSafe(governedSignal)
        );

        // 2.2 获取更新后的上下文包
        // 状态机可能修改了任务状态或添加了新的执行节点
        StructuredContextPackage contextPackage = decision == null ? preContextPackage : decision.getContextPackage();

        // ========================================================================
        // 第三阶段：检测并处理恢复场景
        // ========================================================================
        // 目标：识别中断的任务流并执行恢复策略

        // 3.1 解析恢复触发条件
        // 检查是否存在以下情况：
        // - 任务被用户主动中断（pause/cancel）
        // - 执行过程中出现异常需要重试
        // - 上下文失效需要重新检索
        // - 外部依赖变化导致计划过时
        RecoveryTrigger recoveryTrigger = resolveRecoveryTrigger(userInput, decision, contextPackage);

        if (recoveryTrigger.shouldRecover) {
            // ====================================================================
            // 恢复分支：执行上下文恢复代理
            // ====================================================================

            // 3.2 记录恢复事件的转换轨迹
            // 用于分析恢复频率和成功率，优化容错机制
            stateTransitionTraceLogger.log(
                    transitionTraceId,
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                    contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                    "CHAT",
                    "recovery",
                    contextPackage == null || contextPackage.getContextState() == null ? "" : nullSafe(contextPackage.getContextState().getLatestContextSnapshotId()),
                    recoveryTrigger.recoveryEvent
            );

            // 3.3 执行恢复代理
            // 根据恢复事件类型采取不同策略：
            // - RESUME: 从中断点继续执行
            // - RETRY: 重试失败的节点
            // - REFRESH: 刷新过期的上下文
            // - REPLAN: 重新规划任务路径
            contextPackage = recoveryContextAgent.recover(
                    sessionId,
                    contextPackage,
                    recoveryTrigger.recoveryEvent,
                    recoveryTrigger.interruptReason
            );

            // 3.4 立即执行检索刷新（如需要）
            // 当检测到以下情况时触发：
            // - RAG检索结果已过期（超过TTL）
            // - MCP工具的能力描述发生变化
            // - 用户明确要求更新上下文
            if (shouldRunImmediateRecoveryRefresh(contextPackage, reconstructionResult)) {
                // 执行节点工作集编排，包括：
                // - 重新执行RAG检索和重排序
                // - 刷新MCP工具的元数据
                // - 重新组装证据链和能力清单
                NodeWorksetResult refreshedWorkset = orchestrateNodeWorkset(
                        sessionId,
                        userInput,
                        decision,
                        contextPackage,
                        reconstructionResult
                );

                // 应用刷新结果到上下文包
                contextPackage = applyImmediateRecoveryRefreshResult(contextPackage, reconstructionResult, refreshedWorkset);

                // 持久化立即刷新的审计记录
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        contextPlanId(contextPackage),
                        contextNodeId(contextPackage),
                        "RECOVERY_IMMEDIATE_REFRESH_EXECUTED",
                        "recovery branch executed immediate reretrieve/rerank subflow in current round",
                        toJsonSafe(Map.of(
                                "refreshRagNow", refreshedWorkset != null && nonBlank(refreshedWorkset.getRagQuery()),
                                "refreshMcpNow", refreshedWorkset != null && nonBlank(refreshedWorkset.getMcpDrivenInput()),
                                "reassembleNow", refreshedWorkset != null,
                                "invalidatedEvidenceRefs", refreshedWorkset == null ? List.of() : refreshedWorkset.getInvalidatedEvidenceRefs(),
                                "invalidatedCapabilityNames", refreshedWorkset == null ? List.of() : refreshedWorkset.getInvalidatedCapabilityNames()
                        ))
                );
            }

            // 3.5 记录恢复触发的审计日志
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_TRIGGERED",
                    "recovery branch entered for interrupted flow",
                    toJsonSafe(Map.of(
                            "event", recoveryTrigger.recoveryEvent,
                            "reason", recoveryTrigger.interruptReason
                    ))
            );

            // 3.6 清理恢复状态（如无待处理工作）
            // 避免内存泄漏和状态污染
            if (!hasPendingRecoveryWork(contextPackage)) {
                recoveryStateStore.clear(sessionId);
            }
        } else {
            // ====================================================================
            // 正常分支：无恢复需求
            // ====================================================================

            // 3.7 清理残留的恢复状态
            // 确保不会误触发之前的恢复逻辑
            recoveryStateStore.clear(sessionId);

            // 3.8 记录常规推进的审计日志
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_SKIPPED",
                    "normal chat turn without interrupt/resume event",
                    toJsonSafe(Map.of("input", userInput == null ? "" : userInput))
            );
        }

        // ========================================================================
        // 第四阶段：持久化所有关键状态和审计信息
        // ========================================================================
        // 目标：确保状态可追溯、可恢复、可审计

        // 4.1 记录最终状态转换轨迹
        // 对比初始状态和最终状态的差异，用于性能分析和异常诊断
        stateTransitionTraceLogger.log(
                transitionTraceId,
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                preContextPackage == null || preContextPackage.getTaskState() == null ? "" : preContextPackage.getTaskState().name(),
                contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                "CHAT",
                "writeback",
                contextPackage == null || contextPackage.getContextState() == null ? "" : nullSafe(contextPackage.getContextState().getLatestContextSnapshotId()),
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 4.2 持久化上下文快照
        // 保存到数据库，支持：
        // - 会话恢复：用户下次访问时可继续对话
        // - 状态回滚：出现问题时可恢复到之前的状态
        // - 数据分析：统计用户行为模式和系统性能
        runtimeAuditService.persistContextSnapshot(sessionId, contextPackage);

        // 4.3 持久化编排决策记录
        // 记录状态机的决策依据和结果，用于：
        // - 决策审计：验证状态机逻辑的正确性
        // - 模型优化：分析决策质量并改进算法
        // - 故障排查：定位错误的决策路径
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected by reconstructed input signal",
                toJsonSafe(buildDecisionStatePayload(decision))
        );

        // 4.4 持久化意图重构审计记录
        // 保存意图理解的详细信息，用于：
        // - 重构质量评估：分析意图识别的准确率
        // - 训练数据收集：为意图识别模型提供标注数据
        // - 用户体验优化：发现用户表达的歧义点
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "INPUT_RECONSTRUCTION",
                "input reconstructed before RAG/MCP routing",
                toJsonSafe(buildInputReconstructionAuditPayload(userInput, reconstructionResult, contextPackage))
        );

        // ========================================================================
        // 第五阶段：构建并返回编排结果
        // ========================================================================
        // 将处理结果封装为标准响应对象，供下游服务使用
        return TaskOrchestrationResult.builder()
                .decision(decision)                      // 状态机的编排决策，包含下一步执行计划
                .contextPackage(contextPackage)          // 完整的结构化上下文，包含所有状态信息
                .reconstructionResult(reconstructionResult) // 意图重构的详细结果
                .recovered(recoveryTrigger.shouldRecover)   // 标记是否执行了恢复流程
                .recoveryEvent(recoveryTrigger.recoveryEvent) // 恢复事件类型（如果触发）
                .interruptReason(recoveryTrigger.interruptReason) // 中断原因说明（如果触发）
                .build();
    }



    @Override
    public TaskOrchestrationResult orchestrateSystemRecovery(String sessionId,
                                                             String userInput,
                                                             String eventType,
                                                             Map<String, Object> eventPayload,
                                                             String recoveryEvent,
                                                             String interruptReason) {
        /**
         * 系统恢复分支从系统事件切入，先驱动状态机再执行恢复代理，用于承接非用户输入触发的修复流程。
         */
        OrchestrationDecision decision = eventIngressService.ingestSystemEvent(
                sessionId,
                eventType == null || eventType.isBlank() ? "SYSTEM" : eventType,
                eventPayload == null ? Map.of() : eventPayload
        );
        StructuredContextPackage contextPackage = decision == null ? null : decision.getContextPackage();
        String effectiveRecoveryEvent = recoveryEvent == null || recoveryEvent.isBlank()
                ? "SYSTEM_RECOVERY"
                : recoveryEvent;
        String effectiveInterruptReason = interruptReason == null || interruptReason.isBlank()
                ? "SYSTEM_EVENT"
                : interruptReason;
        contextPackage = recoveryContextAgent.recover(
                sessionId,
                contextPackage,
                effectiveRecoveryEvent,
                effectiveInterruptReason
        );
        InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                sessionId,
                userInput,
                contextPackage,
                decision == null ? null : decision.getTaskState(),
                decision == null ? null : decision.getRelationalState()
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "RECOVERY_TRIGGERED",
                "recovery branch entered from system event",
                toJsonSafe(Map.of(
                        "event", effectiveRecoveryEvent,
                        "reason", effectiveInterruptReason,
                        "eventType", eventType == null ? "SYSTEM" : eventType
                ))
        );
        runtimeAuditService.persistContextSnapshot(sessionId, contextPackage);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected by system recovery event",
                toJsonSafe(buildDecisionStatePayload(decision))
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "INPUT_RECONSTRUCTION",
                "input reconstructed in recovery branch",
                toJsonSafe(buildInputReconstructionAuditPayload(userInput, reconstructionResult, contextPackage))
        );
        return TaskOrchestrationResult.builder()
                .decision(decision)
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .recovered(true)
                .recoveryEvent(effectiveRecoveryEvent)
                .interruptReason(effectiveInterruptReason)
                .build();
    }

    @Override
    public NodeWorksetResult orchestrateNodeWorkset(String sessionId,
                                                    String userInput,
                                                    OrchestrationDecision decision,
                                                    StructuredContextPackage contextPackage,
                                                    InputReconstructionResult reconstructionResult) {
        // 第一步：评估输入重构的召回就绪状态
        // 检查输入重构是否达到召回门槛，如果任务目标不明确则直接阻断，避免无效召回污染上下文
        ReconstructionRecallGate recallGate = evaluateReconstructionRecallGate(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        if (!recallGate.ready()) {
            String reason = recallGate.blockedReason();
            // 持久化阻断状态到存储系统
            persistReconstructionBlockedState(sessionId, decision, contextPackage, reconstructionResult, reason);
            // 记录审计决策日志，包含详细的阻断原因和置信度信息
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "NODE_WORKSET_BLOCKED",
                    "node workset blocked before retrieval due to reconstruction readiness check",
                    toJsonSafe(Map.of(
                            "reason", reason,
                            "hasReconstruction", reconstructionResult != null,
                            "explicitTaskGoal", reconstructionResult == null ? "" : nullSafe(reconstructionResult.getExplicitTaskGoal()),
                            "intentConfidence", recallGate.intentConfidence(),
                            "intentConfidenceMin", recallGate.minIntentConfidence(),
                            "missingSlots", recallGate.missingSlots(),
                            "missingSlotsMax", recallGate.maxMissingSlots(),
                            "requiredEntities", recallGate.requiredEntities(),
                            "entityCount", recallGate.entityCount()
                    ))
            );
            return blockedNodeWorksetResult(reason);
        }

        // 第二步：构建追踪上下文
        // 生成工作集追踪ID和元数据，用于后续各阶段的链路追踪和审计
        String worksetTraceId = buildTraceId("NODE_WORKSET", sessionId, contextPlanId(contextPackage), contextNodeId(contextPackage));
        Map<String, Object> traceMeta = buildTraceMeta(contextPackage, contextNodeId(contextPackage), worksetTraceId, "NODE_WORKSET");

        // 第三步：记录召回阶段的状态迁移日志
        stateTransitionTraceLogger.log(
                worksetTraceId,
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name(),
                decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name(),
                "NODE_WORKSET",
                "recall",
                contextPackage == null || contextPackage.getContextState() == null ? "" : nullSafe(contextPackage.getContextState().getLatestContextSnapshotId()),
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 第四步：消费恢复刷新计划并构建MCP查询
        // 获取是否需要刷新RAG、MCP或重新组装的标志，以及已失效的证据和能力列表
        RecoveryRefreshPlan refreshPlan = consumeRecoveryRefreshPlan(contextPackage);
        String mcpDrivenInput = mcpQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );

        // 校验MCP查询是否成功构建，失败则阻断节点工作集生成
        if (!nonBlank(mcpDrivenInput)) {
            persistReconstructionBlockedState(sessionId, decision, contextPackage, reconstructionResult, "mcp_query_not_buildable");
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "NODE_WORKSET_BLOCKED",
                    "node workset blocked because mcp query is not buildable",
                    toJsonSafe(Map.of(
                            "reason", "mcp_query_not_buildable"
                    ))
            );
            return blockedNodeWorksetResult("mcp_query_not_buildable");
        }

        // 根据刷新计划在MCP查询中附加刷新标志
        if (refreshPlan.reassembleNow) {
            mcpDrivenInput = appendRefreshFlag(mcpDrivenInput, "reassembly");
        }
        if (refreshPlan.refreshMcpNow) {
            mcpDrivenInput = appendRefreshFlag(mcpDrivenInput, "mcp");
        }

        // 第五步：MCP能力预路由和预排序
        // 调用能力策略路由服务获取原始MCP候选能力列表（最多24个）
        List<Map<String, Object>> rawMcpCandidates = capabilityPolicyRouterService.routeForContext(
                sessionId,
                mcpDrivenInput,
                decision == null ? null : decision.getTaskState(),
                decision == null ? null : decision.getRelationalState(),
                24
        );

        // 过滤掉已失效的能力候选项
        rawMcpCandidates = filterInvalidatedCapabilities(rawMcpCandidates, refreshPlan.invalidatedCapabilityNames);

        // 对MCP候选能力进行预排序，形成候选能力池供后续全局重排使用
        List<Map<String, Object>> mcpPreRankedCandidates = mcpCandidatePreRank.preRank(
                mcpDrivenInput,
                rawMcpCandidates,
                reconstructionResult,
                decision == null ? null : decision.getTaskState(),
                24
        );
        if (mcpPreRankedCandidates == null) {
            mcpPreRankedCandidates = List.of();
        }

        // 记录MCP预排序结果的审计日志
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "MCP_PRE_RANK",
                "system-level pre-rank before global semantic rerank",
                toJsonSafe(withTraceMeta(new LinkedHashMap<>(Map.of(
                        "query", mcpDrivenInput,
                        "rawCandidateCount", rawMcpCandidates.size(),
                        "candidateCount", mcpPreRankedCandidates.size(),
                        "candidates", mcpPreRankedCandidates
                )), traceMeta, "MCP_PRE_RANK", contextNodeId(contextPackage)))
        );

        // 第六步：记录重排阶段的状态迁移日志
        stateTransitionTraceLogger.log(
                worksetTraceId,
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name(),
                decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name(),
                "NODE_WORKSET",
                "rerank",
                contextPackage == null || contextPackage.getContextState() == null ? "" : nullSafe(contextPackage.getContextState().getLatestContextSnapshotId()),
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 第七步：构建RAG和记忆检索查询
        // 基于重构结果和任务状态分别生成知识检索查询和记忆检索查询
        String ragQuery = ragQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        String memoryQuery = memoryQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );

        // 校验查询构建结果，如果任一查询构建失败则阻断节点工作集生成
        if (!nonBlank(ragQuery) || !nonBlank(memoryQuery)) {
            String blockedReason = !nonBlank(ragQuery) ? "rag_query_not_buildable" : "memory_query_not_buildable";
            persistReconstructionBlockedState(sessionId, decision, contextPackage, reconstructionResult, blockedReason);
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "NODE_WORKSET_BLOCKED",
                    "node workset blocked because retrieval query is not buildable",
                    toJsonSafe(Map.of(
                            "reason", blockedReason
                    ))
            );
            return blockedNodeWorksetResult(blockedReason);
        }

        // 根据刷新计划在查询中附加刷新标志
        if (refreshPlan.refreshRagNow || refreshPlan.reassembleNow) {
            ragQuery = appendRefreshFlag(ragQuery, "rag");
            memoryQuery = appendRefreshFlag(memoryQuery, "memory");
        }

        // 第八步：初始化结果变量
        List<EvidenceBlock> selectedKnowledgeEvidenceBlocks = List.of();
        List<String> selectedKnowledge = List.of();
        List<String> selectedMemory = List.of();
        List<String> selectedPreference = List.of();
        ContextRerankResult rerankResult = null;

        try {
            // 第九步：执行多路召回
            // 分别执行知识、偏好、记忆召回，再通过全局重排代理整合多源证据和MCP候选

            // 构建检索对话上下文和允许的路由列表
            List<ConversationMessage> conversationContext = buildRetrievalConversationContext(contextPackage);
            List<RetrievalRoute> allowedRoutes = resolveAllowedRoutes(decision);

            // 如果需要刷新RAG或重新组装，则允许所有路由以获取最新数据
            if (refreshPlan.refreshRagNow || refreshPlan.reassembleNow) {
                allowedRoutes = RetrievalRoute.all();
            }

            // 构建治理信号和检索选项
            GovernedSignal governedSignal = buildGovernedSignal(userInput, reconstructionResult);
            RetrievalOptions options = resolveRetrievalOptions(governedSignal, decision);

            // 如果需要刷新，则调整检索选项以提高容错性
            if (refreshPlan.refreshRagNow || refreshPlan.reassembleNow) {
                options = RetrievalOptions.builder()
                        .debug(options.isDebug())
                        .maxLatencyMs(Math.max(options.getMaxLatencyMs(), 1800L))
                        .build();
            }

            // 执行RAG检索（知识和偏好）
            RetrievalRequest request = RetrievalRequest.builder()
                    .query(ragQuery)
                    .sessionId(sessionId)
                    .conversationContext(conversationContext)
                    .allowedRoutes(allowedRoutes)
                    .sourceScope(List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.PREFERENCE))
                    .options(options)
                    .build();
            RetrievalResponse ragResponse = retrievalService.retrieve(request);

            // 执行记忆检索
            RetrievalRequest memoryRequest = RetrievalRequest.builder()
                    .query(memoryQuery)
                    .sessionId(sessionId)
                    .conversationContext(conversationContext)
                    .allowedRoutes(allowedRoutes)
                    .sourceScope(List.of(RetrievalSource.MEMORY))
                    .options(options)
                    .build();
            RetrievalResponse memoryResponse = retrievalService.retrieve(memoryRequest);

            // 合并两路检索响应并过滤已失效的证据
            RetrievalResponse response = mergeRetrievalResponses(ragResponse, memoryResponse);
            response = filterInvalidatedEvidences(response, refreshPlan.invalidatedEvidenceRefs);

            // 构建召回追踪负载，记录各路召回的原始候选结果
            Map<String, Object> recallTracePayload = new LinkedHashMap<>();
            recallTracePayload.put("ragQuery", ragQuery);
            recallTracePayload.put("memoryQuery", memoryQuery);
            recallTracePayload.put("mcpQuery", mcpDrivenInput);
            recallTracePayload.put("allowedRoutes", allowedRoutes);
            recallTracePayload.put("knowledgeCandidates", getEvidences(ragResponse, RetrievalSource.KNOWLEDGE));
            recallTracePayload.put("memoryCandidates", getEvidences(memoryResponse, RetrievalSource.MEMORY));
            recallTracePayload.put("preferenceCandidates", getEvidences(ragResponse, RetrievalSource.PREFERENCE));
            recallTracePayload.put("mcpPreRankCandidates", mcpPreRankedCandidates);
            recallTracePayload.put("recoveryRefreshPlan", Map.of(
                    "needRagRefresh", refreshPlan.refreshRagNow,
                    "needMcpRefresh", refreshPlan.refreshMcpNow,
                    "needReassembly", refreshPlan.reassembleNow,
                    "invalidatedEvidenceRefs", refreshPlan.invalidatedEvidenceRefs,
                    "invalidatedCapabilityNames", refreshPlan.invalidatedCapabilityNames,
                    "invalidationReasonsByRef", refreshPlan.invalidationReasonsByRef
            ));

            // 记录多路召回原始结果的审计日志
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "MULTI_ROUTE_RECALL_TRACE",
                    "raw multi-route retrieval candidates before global rerank",
                    toJsonSafe(withTraceMeta(recallTracePayload, traceMeta, "MULTI_ROUTE_RECALL", contextNodeId(contextPackage)))
            );

            // 记录各通道底部重排的标准化追踪日志
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RERANK_TRACE_BOTTOM_CHANNELS",
                    "standardized per-channel bottom rerank trace before global semantic rerank",
                    toJsonSafe(buildBottomRerankTracePayload(
                            response,
                            mcpPreRankedCandidates,
                            ragQuery,
                            memoryQuery,
                            mcpDrivenInput
                    ))
            );

            // 第十步：执行全局上下文重排
            // 调用全局重排代理整合多源证据和MCP候选，生成统一的重排结果
            rerankResult = globalContextRerankAgent.rerank(
                    reconstructionResult,
                    contextPackage,
                    response,
                    mcpPreRankedCandidates,
                    decision == null ? null : decision.getTaskState()
            );

            // 记录全局重排结果的审计日志
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "GLOBAL_CONTEXT_RERANK",
                    "cross-source rerank after retrieval",
                    toJsonSafe(withTraceMeta(new LinkedHashMap<>(Map.of("result", rerankResult == null ? Map.of() : rerankResult)), traceMeta, "GLOBAL_RERANK", contextNodeId(contextPackage)))
            );

            // 记录重排追踪日志
            rerankTraceLogger.log(sessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), rerankResult, traceMeta);

            // 第十一步：从重排结果中提取选中的知识证据块
            // 优先级：重排结果中的证据块 > 重排结果中的知识块 > 降级使用原始召回结果
            if (rerankResult != null && rerankResult.getSelectedKnowledgeEvidenceBlocks() != null
                    && !rerankResult.getSelectedKnowledgeEvidenceBlocks().isEmpty()) {
                selectedKnowledgeEvidenceBlocks = rerankResult.getSelectedKnowledgeEvidenceBlocks();
                selectedKnowledge = selectedKnowledgeEvidenceBlocks.stream()
                        .map(this::toEvidenceSnippet)
                        .filter(item -> item != null && !item.isBlank())
                        .toList();
            } else if (rerankResult != null && rerankResult.getSelectedKnowledgeBlocks() != null
                    && !rerankResult.getSelectedKnowledgeBlocks().isEmpty()) {
                selectedKnowledge = rerankResult.getSelectedKnowledgeBlocks();
            } else {
                selectedKnowledgeEvidenceBlocks = evidenceBlockBuilder.buildKnowledgeBlocks(getEvidences(response, RetrievalSource.KNOWLEDGE));
                selectedKnowledge = selectedKnowledgeEvidenceBlocks.stream()
                        .map(this::toEvidenceSnippet)
                        .toList();
            }

            // 第十二步：合并记忆片段
            // 合并原始召回的记忆片段和重排结果中选中的记忆提示
            List<String> mergedMemory = new ArrayList<>(toMemorySnippets(response));
            if (rerankResult != null && rerankResult.getSelectedMemoryHints() != null) {
                mergedMemory.addAll(rerankResult.getSelectedMemoryHints());
            }
            selectedMemory = mergedMemory;

            // 提取偏好片段
            selectedPreference = toPreferenceSnippets(response);
        } catch (Exception e) {
            // 第十三步：异常处理与降级策略
            // 当召回或重排出现异常时，保留空结果并记录审计日志，避免因单路故障阻断整个节点执行
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "NODE_ORCHESTRATION_RECALL_FAILED",
                    "recall/rerank branch fallback",
                    toJsonSafe(Map.of(
                            "error", nullSafe(e.getMessage()),
                            "mcpQuery", mcpDrivenInput,
                            "ragQuery", ragQuery,
                            "memoryQuery", memoryQuery
                    ))
            );
            selectedKnowledgeEvidenceBlocks = List.of();
            selectedKnowledge = List.of();
            selectedMemory = List.of();
            selectedPreference = List.of();
        }

        // 第十四步：解析执行候选项和MCP资源提示
        // 根据重排结果提取真正用于执行的能力候选和资源提示，输出节点级工作集结果

        // 解析执行候选项：如果需要重新组装则使用空列表，否则使用预排序的MCP候选
        List<Resource> executionCandidates = resolveExecutionCandidates(
                rerankResult,
                refreshPlan.reassembleNow ? List.of() : mcpPreRankedCandidates
        );

        // 提取MCP资源提示：从重排结果中提取各类候选能力名称，限制最多8个
        List<String> mcpResourceHints = mcpResourceHintExtractor.extract(
                rerankResult == null ? List.of() : rerankResult.getSelectedPromptCandidates(),
                rerankResult == null ? List.of() : rerankResult.getSelectedResourceCandidates(),
                rerankResult == null ? List.of() : rerankResult.getSelectedWorkflowCandidates(),
                rerankResult == null ? List.of() : rerankResult.getSelectedToolCandidates(),
                8
        );

        // 提取各类候选能力的名称列表
        List<String> selectedToolCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedToolCandidates());
        List<String> selectedPromptCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedPromptCandidates());
        List<String> selectedResourceCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedResourceCandidates());
        List<String> selectedWorkflowCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedWorkflowCandidates());

        // 合并提示词、资源和 workflows 的名称列表
        List<String> selectedPromptResourceNames = mergeDistinct(
                mergeDistinct(selectedPromptCandidateNames, selectedResourceCandidateNames),
                selectedWorkflowCandidateNames
        );

        // 第十五步：构建并返回节点工作集结果
        // 将所有召回、重排和提取的结果组装成完整的节点工作集对象
        return NodeWorksetResult.builder()
                .mcpDrivenInput(mcpDrivenInput)
                .ragQuery(ragQuery)
                .memoryQuery(memoryQuery)
                .mcpPreRankedCandidates(mcpPreRankedCandidates)
                .rerankResult(rerankResult)
                .rerankRationaleByNode(rerankResult == null ? Map.of() : safeStringMap(rerankResult.getRationaleByNode()))
                .selectedKnowledgeEvidenceBlocks(selectedKnowledgeEvidenceBlocks)
                .selectedKnowledgeEvidenceRefs(extractKnowledgeEvidenceRefs(selectedKnowledgeEvidenceBlocks))
                .selectedKnowledgeSnippets(selectedKnowledge)
                .selectedMemorySnippets(selectedMemory)
                .selectedPreferenceSnippets(selectedPreference)
                .selectedToolCandidateNames(selectedToolCandidateNames)
                .selectedMcpToolCandidateNames(selectedToolCandidateNames)
                .selectedPromptCandidateNames(selectedPromptCandidateNames)
                .selectedResourceCandidateNames(selectedResourceCandidateNames)
                .selectedWorkflowCandidateNames(selectedWorkflowCandidateNames)
                .selectedPromptResourceNames(selectedPromptResourceNames)
                .invalidatedEvidenceRefs(refreshPlan.invalidatedEvidenceRefs)
                .invalidatedCapabilityNames(refreshPlan.invalidatedCapabilityNames)
                .invalidationReasonsByRef(refreshPlan.invalidationReasonsByRef)
                .executionCandidates(executionCandidates)
                .mcpResourceHints(mcpResourceHints)
                .build();
    }


    /**
     * 编排工具决策节点的完整执行流程，负责从上下文组装、快照持久化、工具调用到语义解析的端到端处理。
     *
     * <p>该方法 orchestrates 以下核心步骤：</p>
     * <ol>
     *   <li>从节点工作集中提取多层记忆、知识证据、候选资源等执行所需的工作集数据</li>
     *   <li>构建工具决策专用的上下文模板策略，组装包含完整信息的决策上下文</li>
     *   <li>将工具调用上下文存入 ThreadLocal，供下游治理机制访问</li>
     *   <li>保存工具决策前后的两份快照（pre-tool 和 tool-decision），记录审计日志</li>
     *   <li>通过治理机制执行工具调用，捕获执行轨迹、异常信息和耗时</li>
     *   <li>持久化工具执行轨迹到数据库，并通过事件入口服务上报工具结果</li>
     *   <li>对工具执行结果进行语义分析，生成结构化的工具语义结果</li>
     *   <li>立即写回工具语义状态和原始引用，供后续轮次使用</li>
     * </ol>
     *
     * <p>异常处理：</p>
     * <ul>
     *   <li>工具调用失败时捕获异常，记录错误信息和状态为 FAILED</li>
     *   <li>无论成功或失败，finally 块都会执行轨迹持久化和事件上报</li>
     *   <li>异常会重新抛出，由上层调用方决定如何处理</li>
     * </ul>
     *
     * @param sessionId 会话标识，用于追踪和关联用户会话
     * @param userInput 用户原始输入文本
     * @param decision 编排决策对象，包含任务运行状态和关系型记忆状态
     * @param contextPackage 结构化上下文包，包含任务状态、恢复状态、检索状态等完整上下文信息
     * @param reconstructionResult 输入重构结果，包含明确的任務目标、意图分类等信息
     * @param nodeWorksetResult 节点工作集结果，包含：
     *                          - mcpDrivenInput: MCP 驱动输入文本
     *                          - rerankResult: 上下文重排结果（含工具、Prompt、资源、工作流候选）
     *                          - selectedKnowledgeEvidenceBlocks: 选中的知识证据块
     *                          - selectedKnowledgeSnippets: 选中的知识片段
     *                          - selectedMemorySnippets: 选中的记忆片段
     *                          - selectedPreferenceSnippets: 选中的偏好片段
     *                          - executionCandidates: 最终保留的执行候选资源
     *                          - mcpResourceHints: 输出给后续阶段的 MCP 资源提示
     * @return ToolDecisionNodeResult 工具决策节点结果，包含：
     *         - toolContext: 组装后的工具上下文文本（工具执行的输出）
     *         - rawToolResultChannel: 原始工具结果通道数据（Map结构，含上下文、轨迹、引用）
     *         - toolTraceRefs: 工具调用轨迹的历史引用列表（用于后续追溯）
     *         - toolSemantic: 工具语义分析结果（结构化的语义理解）
     *         - preToolSnapshotId: 工具决策前的上下文快照标识
     *         - toolDecisionSnapshotId: 工具决策上下文的快照标识
     */
    @Override
    public ToolDecisionNodeResult orchestrateToolDecisionNode(String sessionId,
                                                              String userInput,
                                                              OrchestrationDecision decision,
                                                              StructuredContextPackage contextPackage,
                                                              InputReconstructionResult reconstructionResult,
                                                              NodeWorksetResult nodeWorksetResult) {
        // 第一步：安全化处理输入参数并从节点工作集中提取执行所需的工作集数据
        String safeSessionId = sessionId == null ? "" : sessionId;
        String safeUserInput = userInput == null ? "" : userInput;
        ContextRerankResult rerankResult = nodeWorksetResult == null ? null : nodeWorksetResult.getRerankResult();
        String mcpDrivenInput = nodeWorksetResult == null ? "" : nullSafe(nodeWorksetResult.getMcpDrivenInput());
        List<Resource> executionCandidates = nodeWorksetResult == null || nodeWorksetResult.getExecutionCandidates() == null
                ? List.of()
                : nodeWorksetResult.getExecutionCandidates();
        List<String> mcpResourceHints = nodeWorksetResult == null || nodeWorksetResult.getMcpResourceHints() == null
                ? List.of()
                : nodeWorksetResult.getMcpResourceHints();
        List<String> knowledgeSnippets = nodeWorksetResult != null && nodeWorksetResult.getSelectedKnowledgeSnippets() != null
                ? nodeWorksetResult.getSelectedKnowledgeSnippets()
                : extractTaskKnowledgeSnippets(contextPackage);
        List<String> preferenceSnippets = mergeDistinct(
                extractRelationalPreferenceSnippets(contextPackage),
                nodeWorksetResult == null ? List.of() : nodeWorksetResult.getSelectedPreferenceSnippets()
        );
        List<String> longTermMemorySnippets = extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = extractWorkingMemorySnippets(contextPackage);
        List<String> runtimeMemorySnippets = extractRuntimeMessageSnippets(contextPackage);
        List<String> ragMemorySnippets = nodeWorksetResult == null || nodeWorksetResult.getSelectedMemorySnippets() == null
                ? List.of()
                : nodeWorksetResult.getSelectedMemorySnippets();
        List<EvidenceBlock> knowledgeEvidenceBlocks = nodeWorksetResult == null || nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks() == null
                ? List.of()
                : nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks();

        // 第二步：解析节点模板策略并构建节点级别的记忆片段集合
        ContextNodeTemplatePolicy nodeTemplatePolicy = resolveNodeTemplatePolicy(decision, contextPackage);
        List<String> memorySnippets = buildNodeScopedMemorySnippets(
                nodeTemplatePolicy,
                workingMemorySnippets,
                runtimeMemorySnippets,
                ragMemorySnippets,
                longTermMemorySnippets
        );

        // 第三步：构建工具决策专用的上下文模板策略并组装完整的决策上下文
        ContextNodeTemplatePolicy toolDecisionPolicy = ContextNodeTemplatePolicy.forToolDecision(
                nodeTemplatePolicy == null ? "" : nodeTemplatePolicy.getCurrentNodeId()
        );
        AssembledContext assembledDecision = contextAssembler.assemble(
                contextPackage,
                reconstructionResult,
                rerankResult,
                null,
                safeUserInput,
                knowledgeEvidenceBlocks,
                workingMemorySnippets,
                runtimeMemorySnippets,
                ragMemorySnippets,
                knowledgeSnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                executionCandidates,
                mcpResourceHints,
                "",
                toolDecisionPolicy,
                null,
                safeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage)
        );
        String assembledDecisionContext = assembledDecision == null ? "" : nullSafe(assembledDecision.getPrompt());

        // 第四步：将工具调用上下文存入 ThreadLocal，供下游治理机制和工具执行器访问
        ToolCallingContextHolder.set(ToolCallingContext.builder()
                .chatSessionKey(safeSessionId)
                .userInput(safeUserInput)
                .toolDecisionInput(mcpDrivenInput)
                .governedInputSignature(ToolDecisionInputSignatureUtil.sign(safeSessionId, mcpDrivenInput, assembledDecisionContext))
                .assembledDecisionContext(assembledDecisionContext)
                .memorySnippets(memorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .preferenceSnippets(preferenceSnippets)
                .longTermMemorySnippets(longTermMemorySnippets)
                .executionCandidates(executionCandidates)
                .mcpResourceHints(mcpResourceHints)
                .toolExecutionTraces(new CopyOnWriteArrayList<>())
                .build());

        // 第五步：保存工具决策前后的两份快照并记录审计日志
        String preToolSnapshotId = contextSnapshotStore.savePreToolDecisionSnapshot(
                safeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                safeUserInput,
                mcpDrivenInput,
                toExecutionCandidateMaps(executionCandidates),
                Map.of(
                        "rerankedToolCandidateCount", rerankResult == null || rerankResult.getSelectedToolCandidates() == null ? 0 : rerankResult.getSelectedToolCandidates().size(),
                        "rerankedPromptCount", rerankResult == null || rerankResult.getSelectedPromptCandidates() == null ? 0 : rerankResult.getSelectedPromptCandidates().size(),
                        "rerankedResourceCount", rerankResult == null || rerankResult.getSelectedResourceCandidates() == null ? 0 : rerankResult.getSelectedResourceCandidates().size(),
                        "rerankedWorkflowCount", rerankResult == null || rerankResult.getSelectedWorkflowCandidates() == null ? 0 : rerankResult.getSelectedWorkflowCandidates().size(),
                        "rerankedPromptResourceCountLegacy", rerankResult == null || rerankResult.getSelectedPromptResources() == null ? 0 : rerankResult.getSelectedPromptResources().size(),
                        "decisionWorksetSnapshotType", "TOOL_DECISION_CONTEXT"
                ),
                buildRawToolResultChannel("", List.of(), "", List.of())
        );
        String toolDecisionSnapshotId = contextSnapshotStore.saveToolDecisionContextSnapshot(
                safeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                assembledDecisionContext,
                assembledDecision == null ? Map.of() : assembledDecision.getCanonicalSections(),
                toExecutionCandidateMaps(executionCandidates),
                assembledDecision == null ? Map.of() : assembledDecision.getSectionTokenCounts(),
                assembledDecision == null ? Map.of() : assembledDecision.getSectionTokenRatios(),
                Map.of(
                        "rerankedToolCandidateCount", rerankResult == null || rerankResult.getSelectedToolCandidates() == null ? 0 : rerankResult.getSelectedToolCandidates().size(),
                        "rerankedPromptCount", rerankResult == null || rerankResult.getSelectedPromptCandidates() == null ? 0 : rerankResult.getSelectedPromptCandidates().size(),
                        "rerankedResourceCount", rerankResult == null || rerankResult.getSelectedResourceCandidates() == null ? 0 : rerankResult.getSelectedResourceCandidates().size(),
                        "rerankedWorkflowCount", rerankResult == null || rerankResult.getSelectedWorkflowCandidates() == null ? 0 : rerankResult.getSelectedWorkflowCandidates().size()
                )
        );
        runtimeAuditService.persistDecisionRecord(
                safeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "CONTEXT_SNAPSHOT_PRE_TOOL",
                "pre-tool snapshot persisted",
                toJsonSafe(Map.of(
                        "snapshotId", preToolSnapshotId == null ? "" : preToolSnapshotId,
                        "toolDecisionSnapshotId", toolDecisionSnapshotId == null ? "" : toolDecisionSnapshotId
                ))
        );

        // 第六步：执行工具调用并捕获执行轨迹、异常信息和耗时
        String toolContext = null;
        String toolStatus = "SUCCESS";
        String toolError = null;
        long toolStartAt = System.currentTimeMillis();
        ToolTraceRefs toolTraceRefs = ToolTraceRefs.empty();
        List<Map<String, Object>> latestToolExecutionTraces = List.of();
        try {
            toolContext = agentService.processToolCallingWithGovernance(
                    ToolDecisionCommand.builder()
                            .sessionId(safeSessionId)
                            .rawUserInput(safeUserInput)
                            .toolDecisionInput(mcpDrivenInput)
                            .policyId(resolvePromptPolicyId(contextPackage))
                            .manualPromptKeys(resolvePromptManualKeys(contextPackage))
                            .personaId(resolvePromptBinding(contextPackage, "personaId", "persona_id"))
                            .sceneId(resolvePromptBinding(contextPackage, "sceneId", "scene_id"))
                            .taskState(decision == null ? null : decision.getTaskState())
                            .relationalState(decision == null ? null : decision.getRelationalState())
                            .modelFamily(resolvePromptModelFamily(contextPackage))
                            .executionCandidates(executionCandidates)
                            .governedInputSignature(ToolDecisionInputSignatureUtil.sign(safeSessionId, mcpDrivenInput, assembledDecisionContext))
                            .assembledDecisionContext(assembledDecisionContext)
                            .build()
            );
        } catch (Exception ex) {
            toolStatus = "FAILED";
            toolError = ex.getMessage();
            throw ex;
        } finally {
            // 第七步：持久化工具执行轨迹并通过事件入口服务上报工具结果
            List<Map<String, Object>> toolExecutionTraces = ToolCallingContextHolder.snapshotToolExecutionTraces();
            latestToolExecutionTraces = toolExecutionTraces == null ? List.of() : toolExecutionTraces;
            ToolCallingContextHolder.clear();
            toolTraceRefs = persistToolExecutionTraces(
                    safeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    safeUserInput,
                    toolContext,
                    toolStatus,
                    toolError,
                    System.currentTimeMillis() - toolStartAt,
                    toolExecutionTraces
            );
            eventIngressService.ingestToolResult(safeSessionId, Map.of(
                    "status", toolStatus.toLowerCase(Locale.ROOT),
                    "toolContext", toolContext == null ? "" : toolContext,
                    "error", toolError == null ? "" : toolError
            ));
        }

        // 第八步：构建原始工具结果通道并对工具执行结果进行语义分析
        Map<String, Object> rawToolResultChannel = buildRawToolResultChannel(
                toolContext,
                latestToolExecutionTraces,
                toolTraceRefs.latestRawRef(),
                toolTraceRefs.historyRefs()
        );
        ToolSemanticResult toolSemanticResult = resolveToolSemanticFromRequest(RoundToolSemanticRequest.builder()
                .sessionId(safeSessionId)
                .contextPackage(contextPackage)
                .taskState(decision == null ? null : decision.getTaskState())
                .explicitTaskGoal(reconstructionResult == null ? "" : reconstructionResult.getExplicitTaskGoal())
                .executionCandidates(executionCandidates)
                .toolContext(toolContext)
                .stage("CHAT_TURN")
                .rawToolResultChannel(rawToolResultChannel)
                .build());

        // 第九步：立即写回工具语义状态和原始引用，供后续轮次使用
        persistImmediateToolSemanticState(
                safeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                contextPackage,
                toolSemanticResult,
                rawToolResultChannel,
                toolTraceRefs.historyRefs()
        );

        // 第十步：构建并返回工具决策节点结果
        return ToolDecisionNodeResult.builder()
                .toolContext(toolContext)
                .rawToolResultChannel(rawToolResultChannel)
                .toolTraceRefs(toolTraceRefs.historyRefs())
                .toolSemantic(toolSemanticResult)
                .preToolSnapshotId(preToolSnapshotId == null ? "" : preToolSnapshotId)
                .toolDecisionSnapshotId(toolDecisionSnapshotId == null ? "" : toolDecisionSnapshotId)
                .build();
    }


    // ... existing code ...

    /**
     * 编排蓝图输入，为计划生成阶段准备完整的上下文信息。
     * <p>
     * 该方法是计划编排的入口点，主要职责包括：
     * 1. 复用标准对话编排链路获取基础上下文和决策结果
     * 2. 基于基础上下文编排节点工作集，收集相关知识和能力候选
     * 3. 构建蓝图草稿，整合重构目标、知识证据和工作流提示
     * 4. 将蓝图阶段的轮次状态持久化到会话存储中
     * <p>
     * 与标准对话编排的区别在于，该方法会额外执行节点工作集编排和蓝图草稿构建，
     * 为主规划服务（MasterPlanningService）提供结构化的输入数据。
     *
     * @param sessionId 会话ID，用于关联用户会话上下文并持久化状态，可为空但会导致状态写入跳过
     * @param userGoal  用户目标描述，用于驱动蓝图生成和意图理解，不能为空或空白
     * @return BlueprintOrchestrationResult 蓝图编排结果，包含：
     *        - contextPackage: 结构化上下文包，包含会话历史、知识召回等信息
     *        - reconstructionResult: 输入重构结果，包含规范化后的任务目标和意图分析
     *        - decision: 编排决策，包含任务状态机产出的下一步行动决策
     *        - nodeWorksetResult: 节点工作集结果，包含重排后的知识证据和能力候选列表
     *        - blueprintDraft: 蓝图草稿，包含显式任务目标、知识证据块和工作流提示
     */
    @Override
    public BlueprintOrchestrationResult orchestrateBlueprintInput(String sessionId, String userGoal) {
        /**
         * 蓝图入口会先复用标准对话编排链路，再补做节点工作集和蓝图草稿，供主规划服务直接消费。
         */

        // 调用标准对话编排链路获取基础上下文和决策结果
        TaskOrchestrationResult orchestrationResult = orchestrateUserInput(sessionId, userGoal);
        StructuredContextPackage contextPackage = orchestrationResult == null ? null : orchestrationResult.getContextPackage();
        InputReconstructionResult reconstructionResult = orchestrationResult == null ? null : orchestrationResult.getReconstructionResult();
        OrchestrationDecision decision = orchestrationResult == null ? null : orchestrationResult.getDecision();

        // 基于基础上下文编排节点工作集并构建蓝图草稿
        NodeWorksetResult nodeWorksetResult = null;
        BlueprintDraft blueprintDraft = null;
        if (contextPackage != null && reconstructionResult != null) {
            nodeWorksetResult = orchestrateNodeWorkset(
                    sessionId,
                    userGoal,
                    decision,
                    contextPackage,
                    reconstructionResult
            );
            blueprintDraft = buildBlueprintDraft(reconstructionResult, contextPackage, nodeWorksetResult, decision);
        }

        // 将蓝图阶段的轮次状态持久化到会话存储，标记当前已进入规划阶段
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                /**
                 * 蓝图入口额外写入一份轮次状态，明确当前已进入规划阶段且下一步是生成蓝图。
                 */
                SummaryResult blueprintEntrySummary = SummaryResult.builder()
                        .narrativeSummary("blueprint_input_orchestrated")
                        .stateSnapshot(Map.of(
                                "currentStage", decision == null || decision.getTaskState() == null ? "PLANNING" : decision.getTaskState().name(),
                                "nextStep", "generate_blueprint",
                                "source", "BLUEPRINT_ENTRY"
                        ))
                        .build();
                writeRoundState(RoundStateWriteRequest.builder()
                        .sessionId(sessionId)
                        .decision(decision)
                        .contextPackage(contextPackage)
                        .reconstruction(reconstructionResult)
                        .rerankResult(nodeWorksetResult == null ? null : nodeWorksetResult.getRerankResult())
                        .summaryResult(blueprintEntrySummary)
                        .ragQuery(nodeWorksetResult == null ? "" : nodeWorksetResult.getRagQuery())
                        .memoryQuery(nodeWorksetResult == null ? "" : nodeWorksetResult.getMemoryQuery())
                        .mcpQuery(nodeWorksetResult == null ? "" : nodeWorksetResult.getMcpDrivenInput())
                        .retrievalPlanOverrides(buildBlueprintEntryOverrides(blueprintDraft))
                        .build());
            } catch (Exception ignore) {
            }
        }

        // 组装并返回蓝图编排结果
        return BlueprintOrchestrationResult.builder()
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .decision(decision)
                .nodeWorksetResult(nodeWorksetResult)
                .blueprintDraft(blueprintDraft)
                .build();
    }

    // ... existing code ...


    @Override
    public SummaryOrchestrationResult orchestrateSummary(String sessionId,
                                                         String userInput,
                                                         String assistantReply,
                                                         StructuredContextPackage contextPackage,
                                                         List<EvidenceBlock> activeEvidenceBlocks,
                                                         List<String> activeMcpResourceHints,
                                                         ToolSemanticResult latestToolSemanticResult,
                                                         boolean replaceHistory,
                                                         String triggerSource) {
        /**
         * 先确保存在有效上下文，然后生成统一摘要结果，供历史压缩、状态写回和后续轮次复用。
         */
        StructuredContextPackage effectiveContext = contextPackage;
        if (effectiveContext == null && sessionId != null && !sessionId.isBlank()) {
            effectiveContext = contextCompilerService.compile(sessionId, userInput, null, null);
        }
        SummaryResult summaryResult = summaryAgent.summarize(
                userInput,
                assistantReply,
                effectiveContext,
                activeEvidenceBlocks == null ? List.of() : activeEvidenceBlocks,
                activeMcpResourceHints == null ? List.of() : activeMcpResourceHints,
                latestToolSemanticResult
        );
        SummaryStateSnapshotValidator.ValidationResult snapshotValidation = summaryStateSnapshotValidator.validate(
                summaryResult,
                effectiveContext,
                latestToolSemanticResult
        );
        /**
         * 对摘要快照做校验和归一化，避免无效状态摘要写回到上下文存储。
         */
        if (snapshotValidation != null && snapshotValidation.normalized() != null) {
            summaryResult = snapshotValidation.normalized();
        }
        if (sessionId != null && !sessionId.isBlank()) {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(effectiveContext),
                    contextNodeId(effectiveContext),
                    "SUMMARY_SNAPSHOT_VALIDATION",
                    snapshotValidation != null && snapshotValidation.valid()
                            ? "summary state snapshot validation passed"
                            : "summary state snapshot validation adjusted",
                    toJsonSafe(Map.of(
                            "valid", snapshotValidation != null && snapshotValidation.valid(),
                            "issues", snapshotValidation == null ? List.of("validator_unavailable") : snapshotValidation.issues(),
                            "triggerSource", triggerSource == null ? "" : triggerSource
                    ))
            );
        }
        if (sessionId != null && !sessionId.isBlank()) {
            Map<String, Object> summaryTraceMeta = buildTraceMeta(
                    effectiveContext,
                    contextNodeId(effectiveContext),
                    buildTraceId("SUMMARY", sessionId, contextPlanId(effectiveContext), contextNodeId(effectiveContext)),
                    "SUMMARY"
            );
            summaryTraceLogger.log(
                    sessionId,
                    contextPlanId(effectiveContext),
                    contextNodeId(effectiveContext),
                    userInput,
                    assistantReply,
                    effectiveContext,
                    summaryResult,
                    triggerSource == null || triggerSource.isBlank() ? "TASK_ORCHESTRATOR" : triggerSource,
                    summaryTraceMeta
            );
        }
        ContextState previous = sessionId == null || sessionId.isBlank() ? null : contextStateStore.load(sessionId);
        /**
         * 基于摘要结果重建上下文状态，并按统一阈值策略决定是否用摘要替换历史消息。
         */
        ContextState contextState = buildContextStateFromSummary(
                previous,
                summaryResult,
                effectiveContext,
                activeEvidenceBlocks,
                activeMcpResourceHints,
                latestToolSemanticResult
        );
        if (sessionId != null && !sessionId.isBlank()) {
            contextStateStore.save(sessionId, contextState);
            int shortTermMemorySize = effectiveContext == null || effectiveContext.getRecentMessages() == null
                    ? 0
                    : effectiveContext.getRecentMessages().size();
            boolean hasStateSnapshot = summaryResult != null
                    && summaryResult.getStateSnapshot() != null
                    && !summaryResult.getStateSnapshot().isEmpty();
            boolean meetsThreshold = shortTermMemorySize >= SUMMARY_REPLACE_HISTORY_MIN_TURNS || hasStateSnapshot;
            if (replaceHistory
                    && meetsThreshold
                    && summaryResult != null
                    && summaryResult.getNarrativeSummary() != null
                    && !summaryResult.getNarrativeSummary().isBlank()) {
                String snapshotText = summaryResult.getStateSnapshot() == null || summaryResult.getStateSnapshot().isEmpty()
                        ? ""
                        : toJsonSafe(summaryResult.getStateSnapshot());
                sessionService.replaceHistoryWithSummary(sessionId, summaryResult.getNarrativeSummary(), snapshotText);
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        contextPlanId(effectiveContext),
                        contextNodeId(effectiveContext),
                        "HISTORY_REPLACEMENT_BY_SUMMARY",
                        "history replaced under orchestrator unified summary policy",
                        toJsonSafe(Map.of(
                                "triggerSource", triggerSource == null ? "" : triggerSource,
                                "shortTermMemorySize", shortTermMemorySize,
                                "replaceMinTurns", SUMMARY_REPLACE_HISTORY_MIN_TURNS,
                                "hasStateSnapshot", hasStateSnapshot
                        ))
                );
            } else if (replaceHistory) {
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        contextPlanId(effectiveContext),
                        contextNodeId(effectiveContext),
                        "HISTORY_REPLACEMENT_SKIPPED",
                        "summary replacement skipped by unified threshold policy",
                        toJsonSafe(Map.of(
                                "triggerSource", triggerSource == null ? "" : triggerSource,
                                "shortTermMemorySize", shortTermMemorySize,
                                "replaceMinTurns", SUMMARY_REPLACE_HISTORY_MIN_TURNS,
                                "hasStateSnapshot", hasStateSnapshot,
                                "narrativePresent", summaryResult != null
                                        && summaryResult.getNarrativeSummary() != null
                                        && !summaryResult.getNarrativeSummary().isBlank()
                        ))
                );
            }
        }
        return SummaryOrchestrationResult.builder()
                .contextPackage(effectiveContext)
                .summaryResult(summaryResult)
                .contextState(contextState)
                .build();
    }

    /**
     * 编排并执行主模型调用，完成从上下文组装、Prompt 治理到模型推理的完整流程。
     *
     * <p>该方法 orchestrates 以下核心步骤：</p>
     * <ol>
     *   <li>校验请求参数并提取会话标识、计划ID、节点ID等上下文元数据</li>
     *   <li>解析主模型 Prompt 治理结果，确定最终使用的模板策略</li>
     *   <li>通过上下文组装器构建包含多层记忆、知识证据、工具语义的完整工作集</li>
     *   <li>生成并持久化上下文快照，记录审计日志和链路追踪信息</li>
     *   <li>校验最终 Prompt 是否为空，若为空则阻断执行并返回</li>
     *   <li>调用主模型进行推理，获取原始响应和校验后的回复文本</li>
     *   <li>持久化 Prompt 快照引用关系，供后续轮次追溯使用</li>
     * </ol>
     *
     * <p>阻断场景：</p>
     * <ul>
     *   <li>请求对象为空：返回 blocked=true, reason="request_missing"</li>
     *   <li>最终治理后的工作集为空：返回 blocked=true, reason="final_governed_workset_empty"</li>
     * </ul>
     *
     * @param request 主模型执行请求，包含：
     *                - sessionId: 会话标识
     *                - userInput: 用户原始输入
     *                - contextPackage: 结构化上下文包（含任务状态、恢复状态等）
     *                - reconstructionResult: 输入重构结果
     *                - rerankResult: 上下文重排结果
     *                - toolSemanticResult: 工具语义分析结果
     *                - knowledgeEvidenceBlocks: 入选的知识证据块列表
     *                - workingMemorySnippets: 工作记忆片段列表
     *                - runtimeMemorySnippets: 运行时记忆片段列表
     *                - retrievedMemorySnippets: 检索得到的记忆片段列表
     *                - knowledgeSnippets: 知识片段列表
     *                - preferenceSnippets: 用户偏好片段列表
     *                - longTermMemorySnippets: 长期记忆片段列表
     *                - executionCandidates: 可执行的候选资源集合
     *                - mcpResourceHints: MCP 资源提示列表
     *                - toolContext: 工具上下文文本
     *                - nodeTemplatePolicy: 节点模板策略
     *                - roundSummaryInput: 轮次摘要输入
     *                - planId: 计划ID（可选，缺失时从 contextPackage 提取）
     *                - nodeId: 节点ID（可选，缺失时从 contextPackage 提取）
     *                - stage: 执行阶段名称
     *                - repairSeed: 修复链路种子信息
     *                - rawToolResultChannel: 原始工具结果通道数据（Map结构）
     * @return MainModelOrchestrationResult 主模型编排结果，包含：
     *         - blocked: 是否因前置条件未满足而阻断执行
     *         - blockedReason: 阻断原因说明（仅在 blocked=true 时有值）
     *         - assembledContext: 主模型执行前最终组装的上下文对象
     *         - finalSnapshotId: 最终上下文快照的唯一标识
     *         - finalPrompt: 最终提交给主模型的完整提示词文本
     *         - rawResponse: 主模型返回的原始响应文本（含可能的格式标记）
     *         - validResponse: 经过校验和清洗后的有效响应文本
     *         - replyText: 提取出的最终回复正文（用于展示给用户）
     */
    @Override
    public MainModelOrchestrationResult orchestrateMainModel(MainModelExecutionRequest request) {
        // 第一步：请求参数校验与基础上下文提取
        if (request == null) {
            return MainModelOrchestrationResult.builder()
                    .blocked(true)
                    .blockedReason("request_missing")
                    .finalPrompt("")
                    .rawResponse("")
                    .validResponse("")
                    .replyText("")
                    .build();
        }
        String sessionId = nullSafe(request.getSessionId());
        StructuredContextPackage contextPackage = request.getContextPackage();
        Long planId = request.getPlanId() == null ? contextPlanId(contextPackage) : request.getPlanId();
        Long nodeId = request.getNodeId() == null ? contextNodeId(contextPackage) : request.getNodeId();
        String transitionTraceId = buildTraceId("MAIN_MODEL", sessionId, planId, nodeId);
        Map<String, Object> rawToolResultChannel = request.getRawToolResultChannel() == null ? Map.of() : request.getRawToolResultChannel();
        Map<String, List<String>> activeRefs = buildFinalSnapshotActiveRefs(request, contextPackage);

        // 第二步：记录上下文组装阶段的状态迁移日志
        stateTransitionTraceLogger.log(
                transitionTraceId,
                sessionId,
                planId,
                nodeId,
                contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                nullSafe(request.getStage()),
                "assemble",
                contextPackage == null || contextPackage.getContextState() == null ? "" : nullSafe(contextPackage.getContextState().getLatestContextSnapshotId()),
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 第三步：解析主模型 Prompt 治理结果，确定模板策略和治理规则
        PromptResolveResult mainPromptResolveResult = resolveMainModelPromptAssembly(
                request.getUserInput(),
                contextPackage,
                request.getNodeTemplatePolicy()
        );

        // 第四步：通过上下文组装器构建包含多层记忆、知识证据、工具语义的完整工作集
        AssembledContext assembledContext = contextAssembler.assembleAndSnapshot(
                contextPackage,
                request.getReconstructionResult(),
                request.getRerankResult(),
                request.getToolSemanticResult(),
                request.getUserInput(),
                request.getKnowledgeEvidenceBlocks() == null ? List.of() : request.getKnowledgeEvidenceBlocks(),
                request.getWorkingMemorySnippets() == null ? List.of() : request.getWorkingMemorySnippets(),
                request.getRuntimeMemorySnippets() == null ? List.of() : request.getRuntimeMemorySnippets(),
                request.getRetrievedMemorySnippets() == null ? List.of() : request.getRetrievedMemorySnippets(),
                request.getKnowledgeSnippets() == null ? List.of() : request.getKnowledgeSnippets(),
                request.getPreferenceSnippets() == null ? List.of() : request.getPreferenceSnippets(),
                request.getLongTermMemorySnippets() == null ? List.of() : request.getLongTermMemorySnippets(),
                request.getExecutionCandidates() == null ? List.of() : request.getExecutionCandidates(),
                request.getMcpResourceHints() == null ? List.of() : request.getMcpResourceHints(),
                request.getToolContext(),
                request.getNodeTemplatePolicy(),
                request.getRoundSummaryInput(),
                sessionId,
                planId,
                nodeId,
                rawToolResultChannel,
                activeRefs,
                buildStructuredRecoveryPayload(contextPackage),
                mainPromptResolveResult
        );
        String finalSnapshotId = assembledContext == null ? "" : nullSafe(assembledContext.getSnapshotId());

        // 第五步：记录上下文组装的追踪日志和审计日志
        Map<String, Object> contextTraceMeta = buildTraceMeta(
                contextPackage,
                nodeId,
                buildTraceId("MAIN_MODEL_CONTEXT", sessionId, planId, nodeId),
                "CONTEXT_ASSEMBLY"
        );
        contextTraceLogger.log(sessionId, planId, nodeId, assembledContext, contextTraceMeta);

        if (!sessionId.isBlank()) {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "CONTEXT_SNAPSHOT_FINAL",
                    "final model context snapshot persisted by runtime audit service",
                    toJsonSafe(Map.of("snapshotId", finalSnapshotId))
            );
        }

        AssembledContext assembledWithSnapshot = assembledContext;

        // 第六步：校验最终 Prompt 是否为空，防止无效的主模型调用
        String finalPrompt = assembledWithSnapshot == null ? "" : nullSafe(assembledWithSnapshot.getPrompt());
        if (finalPrompt.isBlank()) {
            stateTransitionTraceLogger.log(
                    transitionTraceId,
                    sessionId,
                    planId,
                    nodeId,
                    contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                    contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                    nullSafe(request.getStage()),
                    "execute",
                    finalSnapshotId,
                    contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
            );
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "CONTEXT_GOVERNANCE_BLOCKED",
                    "main model execution blocked because final governed workset is empty",
                    toJsonSafe(Map.of(
                            "stage", nullSafe(request.getStage()),
                            "snapshotId", finalSnapshotId,
                            "assembledContextPresent", assembledWithSnapshot != null
                    ))
            );
            return MainModelOrchestrationResult.builder()
                    .blocked(true)
                    .blockedReason("final_governed_workset_empty")
                    .assembledContext(assembledWithSnapshot)
                    .finalSnapshotId(finalSnapshotId)
                    .finalPrompt("")
                    .rawResponse("")
                    .validResponse("")
                    .replyText("")
                    .build();
        }

        // 第七步：调用主模型进行推理，获取原始响应和校验后的回复
        Long roundId = resolveRoundId(contextPackage);
        ModelReply modelReply = invokeMainModel(
                finalPrompt,
                request.getRepairSeed() == null ? request.getUserInput() : request.getRepairSeed(),
                contextPackage,
                sessionId,
                roundId,
                nodeId,
                finalSnapshotId
        );

        // 第八步：记录执行完成阶段的状态迁移日志
        stateTransitionTraceLogger.log(
                transitionTraceId,
                sessionId,
                planId,
                nodeId,
                contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name(),
                nullSafe(request.getStage()),
                "writeback",
                finalSnapshotId,
                contextPackage == null || contextPackage.getRecoveryState() == null ? "" : nullSafe(contextPackage.getRecoveryState().getRecoveryEvent())
        );

        // 第九步：持久化 Prompt 快照引用关系，供后续轮次追溯和审计使用
        if (!sessionId.isBlank()) {
            persistPromptSnapshotRefs(sessionId, roundId, nodeId, finalSnapshotId, assembledContext);
        }

        // 第十步：构建并返回主模型编排结果
        return MainModelOrchestrationResult.builder()
                .blocked(false)
                .blockedReason("")
                .assembledContext(assembledWithSnapshot)
                .finalSnapshotId(finalSnapshotId)
                .finalPrompt(finalPrompt)
                .rawResponse(modelReply.raw())
                .validResponse(modelReply.valid())
                .replyText(modelReply.replyText())
                .build();
    }


    @Override
    public void writeRoundState(RoundStateWriteRequest request) {
        /**
         * 统一把轮次决策、摘要、快照、检索查询和工具结果引用写回各类状态存储，维持上下文连续性。
         */
        if (request == null || request.getSessionId() == null || request.getSessionId().isBlank()) {
            return;
        }
        String sessionId = request.getSessionId();
        StructuredContextPackage contextPackage = request.getContextPackage();
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
        InputReconstructionResult reconstruction = request.getReconstruction();
        SummaryResult summaryResult = request.getSummaryResult();
        ContextRerankResult rerankResult = request.getRerankResult();
        ToolSemanticResult toolSemanticResult = request.getToolSemanticResult();
        Map<String, Object> retrievalPlanOverrides = request.getRetrievalPlanOverrides() == null
                ? Map.of()
                : request.getRetrievalPlanOverrides();
        Map<String, Object> taskStatePatch = safeMap(retrievalPlanOverrides.get("task_state_patch"));
        Map<String, Object> retrievalStatePatch = safeMap(retrievalPlanOverrides.get("retrieval_state_patch"));
        if (toolSemanticResult != null) {
            ToolSemanticResultValidator.ValidationResult toolSemanticValidation = toolSemanticResultValidator.validate(toolSemanticResult, contextPackage);
            if (!toolSemanticValidation.valid()) {
                runtimeAuditService.persistDecisionRecord(
                        sessionId,
                        contextPlanId(contextPackage),
                        contextNodeId(contextPackage),
                        "TOOL_SEMANTIC_SCHEMA_INVALID",
                        "tool semantic rejected by schema/state checks during round state write",
                        toJsonSafe(Map.of("issues", toolSemanticValidation.issues()))
                );
            }
            if (toolSemanticValidation.normalized() != null) {
                toolSemanticResult = toolSemanticValidation.normalized();
            }
        }

        Map<String, Object> confirmedSlots = mergeMaps(
                previousTaskState == null ? Map.of() : previousTaskState.getConfirmedSlots(),
                reconstruction == null || reconstruction.getClarifiedEntities() == null ? Map.of() : new LinkedHashMap<>(reconstruction.getClarifiedEntities())
        );
        List<String> pendingQuestions = mergeDistinctList(
                previousTaskState == null ? List.of() : previousTaskState.getPendingQuestions(),
                reconstruction == null || reconstruction.getMissingSlots() == null ? List.of() : reconstruction.getMissingSlots()
        );
        String patchedObjective = firstNonBlank(
                stringValue(taskStatePatch.get("objective")),
                reconstruction == null ? "" : reconstruction.getExplicitTaskGoal()
        );
        String patchedCurrentStage = firstNonBlank(
                stringValue(taskStatePatch.get("current_stage")),
                request.getDecision() == null || request.getDecision().getTaskState() == null ? "UNKNOWN" : request.getDecision().getTaskState().name()
        );
        String patchedCurrentNode = firstNonBlank(
                stringValue(taskStatePatch.get("current_node")),
                String.valueOf(contextNodeId(contextPackage))
        );
        List<String> patchedPendingQuestions = mergeDistinctList(
                pendingQuestions,
                toStringList(taskStatePatch.get("pending_questions"))
        );
        TaskState taskState = TaskState.builder()
                .taskId(String.valueOf(contextPlanId(contextPackage)))
                .sessionId(sessionId)
                .objective(patchedObjective)
                .currentStage(patchedCurrentStage)
                .currentNode(patchedCurrentNode)
                .confirmedSlots(confirmedSlots)
                .pendingQuestions(patchedPendingQuestions)
                .finishedSteps(finishedSteps)
                .failedSteps(failedSteps)
                .retryCount(retryCount)
                .nextActionHint(summaryResult == null || summaryResult.getStateSnapshot() == null ? "continue" : String.valueOf(summaryResult.getStateSnapshot().getOrDefault("nextStep", "continue")))
                .build();
        taskStateStore.save(sessionId, taskState);
        boolean stageChanged = previousTaskState != null
                && previousTaskState.getCurrentStage() != null
                && !previousTaskState.getCurrentStage().equals(taskState.getCurrentStage());
        boolean finishedStepsAdvanced = previousTaskState != null
                && safeSize(finishedSteps) > safeSize(previousTaskState.getFinishedSteps());

        Map<String, Object> retrievalPlan = new LinkedHashMap<>();
        if (!retrievalPlanOverrides.isEmpty()) {
            retrievalPlan.putAll(retrievalPlanOverrides);
        } else {
            retrievalPlan.put("allowedRoutes", resolveAllowedRoutes(request.getDecision()));
            retrievalPlan.put("maxLatencyMs", resolveRetrievalOptions(
                    buildGovernedSignal("", request.getReconstruction()),
                    request.getDecision()
            ).getMaxLatencyMs());
        }
        if (!retrievalStatePatch.isEmpty()) {
            retrievalPlan.put("retrieval_state_patch", retrievalStatePatch);
        }
        RetrievalState retrievalState = RetrievalState.builder()
                .reconstructedIntent(firstNonBlank(
                        stringValue(retrievalStatePatch.get("reconstructed_intent")),
                        reconstruction == null ? "" : reconstruction.getNormalizedUserIntent()
                ))
                .activeQueries(mergeDistinctList(
                        previousRetrievalState == null ? List.of() : previousRetrievalState.getActiveQueries(),
                        mergeDistinct(
                                mergeDistinct(nonBlankList(request.getRagQuery()), nonBlankList(request.getMemoryQuery())),
                                mergeDistinct(
                                        nonBlankList(request.getMcpQuery()),
                                        reconstruction == null ? List.of() : mergeDistinct(
                                                nonBlankList(reconstruction.getReformulatedQueryForRag()),
                                                nonBlankList(reconstruction.getReformulatedQueryForMcp())
                                        )
                                )
                        )))
                .retrievalPlan(retrievalPlan)
                .selectedEvidenceRefs(extractKnowledgeRefs(rerankResult))
                .rerankSummary(rerankResult == null ? "" : toJsonSafe(rerankResult.getRationaleByNode()))
                .build();
        retrievalStateStore.save(sessionId, retrievalState);

        String latestToolRawRef = resolveLatestToolRawResultRef(
                request.getLatestToolRawRef(),
                toolRows,
                previousToolState
        );
        String latestToolRawResultJson = resolveLatestToolRawResultJson(
                request.getRawToolResultChannel(),
                latestToolRawRef,
                toolRows,
                previousToolState
        );
        if (latestToolRawResultJson == null || latestToolRawResultJson.isBlank()) {
            latestToolRawResultJson = request.getRawToolResultChannel() == null || request.getRawToolResultChannel().isEmpty()
                    ? ""
                    : toJsonSafe(request.getRawToolResultChannel());
        }
        String latestToolRawResultDigest = sha256Hex(latestToolRawResultJson);
        String latestToolRawResultPreview = truncate(latestToolRawResultJson, 512);
        List<String> activeToolRefs = resolveActiveToolEvidenceRefs(
                request.getLatestToolHistoryRefs(),
                toolRows
        );
        ToolState toolState = ToolState.builder()
                .lastToolName(resolveLastToolName(toolRows, toolSemanticResult))
                .lastToolInput(reconstruction == null ? "" : reconstruction.getReformulatedQueryForMcp())
                .lastToolStatus(toolSemanticResult == null ? "" : toolSemanticResult.getToolStatus())
                .lastToolRawResultRef(latestToolRawRef)
                .lastToolRawPayloadRef(firstNonBlank(
                        request.getLatestSnapshotId(),
                        previousToolState == null ? "" : previousToolState.getLastToolRawPayloadRef()
                ))
                .lastToolRawResult(firstNonBlank(
                        latestToolRawResultJson,
                        previousToolState == null ? "" : previousToolState.getLastToolRawResult()
                ))
                .lastToolRawResultDigest(firstNonBlank(latestToolRawResultDigest, previousToolState == null ? "" : previousToolState.getLastToolRawResultDigest()))
                .lastToolRawResultPreview(firstNonBlank(latestToolRawResultPreview, previousToolState == null ? "" : previousToolState.getLastToolRawResultPreview()))
                .lastToolSemanticSummary(toolSemanticResult == null ? "" : toolSemanticResult.getBusinessImpact())
                .toolCallHistoryRefs(mergeDistinctList(
                        previousToolState == null ? List.of() : previousToolState.getToolCallHistoryRefs(),
                        mergeDistinct(
                                extractToolHistoryRefs(toolRows),
                                request.getLatestToolHistoryRefs() == null ? List.of() : request.getLatestToolHistoryRefs()
                        )
                ))
                .build();
        toolStateStore.save(sessionId, toolState);

        List<String> knowledgeRefs = extractKnowledgeRefs(rerankResult);
        List<String> memoryRefs = rerankResult == null || rerankResult.getSelectedMemoryHints() == null ? List.of() : rerankResult.getSelectedMemoryHints();
        List<String> mcpPromptRefs = rerankResult == null || rerankResult.getSelectedPromptCandidates() == null
                ? List.of()
                : rerankResult.getSelectedPromptCandidates().stream().map(this::toJsonSafe).toList();
        List<String> mcpResourceRefs = rerankResult == null || rerankResult.getSelectedResourceCandidates() == null
                ? List.of()
                : rerankResult.getSelectedResourceCandidates().stream().map(this::toJsonSafe).toList();
        List<String> mcpWorkflowRefs = rerankResult == null || rerankResult.getSelectedWorkflowCandidates() == null
                ? List.of()
                : rerankResult.getSelectedWorkflowCandidates().stream().map(this::toJsonSafe).toList();
        List<String> mcpToolRefs = rerankResult == null || rerankResult.getSelectedToolCandidates() == null
                ? List.of()
                : rerankResult.getSelectedToolCandidates().stream().map(this::toJsonSafe).toList();
        Map<String, Object> latestStateSnapshot = new LinkedHashMap<>(
                summaryResult == null || summaryResult.getStateSnapshot() == null ? Map.of() : summaryResult.getStateSnapshot()
        );
        ActiveRefGovernanceResult governedKnowledgeRefs = governActiveRefs(
                "knowledge",
                knowledgeRefs,
                previousContextState == null ? List.of() : previousContextState.getActiveKnowledgeRefs(),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMemoryRefs = governActiveRefs(
                "memory",
                memoryRefs,
                previousContextState == null ? List.of() : previousContextState.getActiveMemoryRefs(),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedToolRefs = governActiveRefs(
                "tool",
                activeToolRefs,
                previousContextState == null ? List.of() : previousContextState.getActiveToolEvidenceRefs(),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_TOOL_REF_MAX,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpPromptRefs = governActiveRefs(
                "mcp_prompt",
                mcpPromptRefs,
                previousContextState == null ? List.of() : previousContextState.getActiveMcpPromptRefs(),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpResourceRefs = governActiveRefs(
                "mcp_resource",
                mcpResourceRefs,
                previousContextState == null ? List.of() : previousContextState.getActiveMcpResourceRefs(),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpWorkflowRefs = governActiveRefs(
                "mcp_workflow",
                mcpWorkflowRefs,
                previousContextState == null ? List.of() : previousContextState.getActiveMcpWorkflowRefs(),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpToolRefs = governActiveRefs(
                "mcp_tool",
                mcpToolRefs,
                previousContextState == null ? List.of() : resolveLegacyMcpToolRefs(previousContextState),
                stageChanged,
                finishedStepsAdvanced,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        latestStateSnapshot.put("activeMcpPromptRefs", governedMcpPromptRefs.refs());
        latestStateSnapshot.put("activeMcpResourceRefs", governedMcpResourceRefs.refs());
        latestStateSnapshot.put("activeMcpWorkflowRefs", governedMcpWorkflowRefs.refs());
        latestStateSnapshot.put("activeMcpToolRefs", governedMcpToolRefs.refs());
        latestStateSnapshot.put("activeMcpResourceRefsLegacy", mergeDistinct(governedMcpResourceRefs.refs(), governedMcpToolRefs.refs()));
        ContextState contextState = ContextState.builder()
                .latestNarrativeSummary(summaryResult == null ? "" : nullSafe(summaryResult.getNarrativeSummary()))
                .latestStateSnapshot(latestStateSnapshot)
                .activeKnowledgeRefs(governedKnowledgeRefs.refs())
                .activeMemoryRefs(governedMemoryRefs.refs())
                .activeToolEvidenceRefs(governedToolRefs.refs())
                .activeMcpPromptRefs(governedMcpPromptRefs.refs())
                .activeMcpResourceRefs(governedMcpResourceRefs.refs())
                .activeMcpWorkflowRefs(governedMcpWorkflowRefs.refs())
                .activeMcpToolRefs(governedMcpToolRefs.refs())
                .latestContextSnapshotId(firstNonBlank(
                        request.getLatestSnapshotId(),
                        previousContextState == null ? "" : previousContextState.getLatestContextSnapshotId()
                ))
                .build();
        contextStateStore.save(sessionId, contextState);
    }

    private void persistImmediateToolSemanticState(String sessionId,
                                                   Long planId,
                                                   Long nodeId,
                                                   StructuredContextPackage contextPackage,
                                                   ToolSemanticResult toolSemanticResult,
                                                   Map<String, Object> rawToolResultChannel,
                                                   List<String> explicitHistoryRefs) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            ToolState previousToolState = contextPackage == null ? null : contextPackage.getToolState();
            ContextState previousContextState = contextPackage == null ? null : contextPackage.getContextState();
            String latestToolRawRef = resolveLatestToolRawRefFromChannel(rawToolResultChannel, previousToolState);
            String latestToolRawResultJson = rawToolResultChannel == null || rawToolResultChannel.isEmpty()
                    ? ""
                    : toJsonSafe(rawToolResultChannel);
            String latestToolRawResultDigest = sha256Hex(latestToolRawResultJson);
            String latestToolRawResultPreview = truncate(latestToolRawResultJson, 512);
            List<String> historyRefs = resolveImmediateToolHistoryRefs(explicitHistoryRefs, rawToolResultChannel, previousToolState);

            ToolState immediateToolState = ToolState.builder()
                    .lastToolName(firstNonBlank(
                            toolSemanticResult == null ? "" : toolSemanticResult.getToolName(),
                            previousToolState == null ? "" : previousToolState.getLastToolName()
                    ))
                    .lastToolInput(previousToolState == null ? "" : nullSafe(previousToolState.getLastToolInput()))
                    .lastToolStatus(firstNonBlank(
                            toolSemanticResult == null ? "" : toolSemanticResult.getToolStatus(),
                            previousToolState == null ? "" : previousToolState.getLastToolStatus()
                    ))
                    .lastToolRawResultRef(firstNonBlank(
                            latestToolRawRef,
                            previousToolState == null ? "" : previousToolState.getLastToolRawResultRef()
                    ))
                    .lastToolRawPayloadRef(previousToolState == null ? "" : nullSafe(previousToolState.getLastToolRawPayloadRef()))
                    .lastToolRawResult(firstNonBlank(
                            latestToolRawResultJson,
                            previousToolState == null ? "" : previousToolState.getLastToolRawResult()
                    ))
                    .lastToolRawResultDigest(firstNonBlank(
                            latestToolRawResultDigest,
                            previousToolState == null ? "" : previousToolState.getLastToolRawResultDigest()
                    ))
                    .lastToolRawResultPreview(firstNonBlank(
                            latestToolRawResultPreview,
                            previousToolState == null ? "" : previousToolState.getLastToolRawResultPreview()
                    ))
                    .lastToolSemanticSummary(firstNonBlank(
                            toolSemanticResult == null ? "" : toolSemanticResult.getBusinessImpact(),
                            previousToolState == null ? "" : previousToolState.getLastToolSemanticSummary()
                    ))
                    .toolCallHistoryRefs(mergeDistinctList(
                            previousToolState == null ? List.of() : previousToolState.getToolCallHistoryRefs(),
                            historyRefs
                    ))
                    .build();
            toolStateStore.save(sessionId, immediateToolState);

            Map<String, Object> latestStateSnapshot = new LinkedHashMap<>(
                    previousContextState == null || previousContextState.getLatestStateSnapshot() == null
                            ? Map.of()
                            : previousContextState.getLatestStateSnapshot()
            );
            ActiveRefGovernanceResult governedToolRefs = governActiveRefs(
                    "tool",
                    historyRefs,
                    previousContextState == null ? List.of() : previousContextState.getActiveToolEvidenceRefs(),
                    false,
                    false,
                    ACTIVE_TOOL_REF_MAX,
                    latestStateSnapshot
            );
            latestStateSnapshot.put("latestToolConclusion", firstNonBlank(
                    toolSemanticResult == null ? "" : toolSemanticResult.getBusinessImpact(),
                    previousContextState == null || previousContextState.getLatestStateSnapshot() == null
                            ? ""
                            : stringValue(previousContextState.getLatestStateSnapshot().get("latestToolConclusion"))
            ));

            ContextState immediateContextState = ContextState.builder()
                    .latestNarrativeSummary(previousContextState == null ? "" : nullSafe(previousContextState.getLatestNarrativeSummary()))
                    .latestStateSnapshot(latestStateSnapshot)
                    .activeKnowledgeRefs(previousContextState == null ? List.of() : toStringList(previousContextState.getActiveKnowledgeRefs()))
                    .activeMemoryRefs(previousContextState == null ? List.of() : toStringList(previousContextState.getActiveMemoryRefs()))
                    .activeToolEvidenceRefs(governedToolRefs.refs())
                    .activeMcpPromptRefs(previousContextState == null ? List.of() : toStringList(previousContextState.getActiveMcpPromptRefs()))
                    .activeMcpResourceRefs(previousContextState == null ? List.of() : toStringList(previousContextState.getActiveMcpResourceRefs()))
                    .activeMcpWorkflowRefs(previousContextState == null ? List.of() : toStringList(previousContextState.getActiveMcpWorkflowRefs()))
                    .activeMcpToolRefs(previousContextState == null ? List.of() : toStringList(previousContextState.getActiveMcpToolRefs()))
                    .latestContextSnapshotId(previousContextState == null ? "" : nullSafe(previousContextState.getLatestContextSnapshotId()))
                    .build();
            contextStateStore.save(sessionId, immediateContextState);

            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_IMMEDIATE_WRITEBACK",
                    "tool semantic immediate state writeback persisted",
                    toJsonSafe(Map.of(
                            "toolRawRef", latestToolRawRef == null ? "" : latestToolRawRef,
                            "semanticStatus", toolSemanticResult == null ? "" : nullSafe(toolSemanticResult.getToolStatus()),
                            "historyRefs", historyRefs == null ? List.of() : historyRefs
                    ))
            );
        } catch (Exception ex) {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_IMMEDIATE_WRITEBACK_FAILED",
                    "tool semantic immediate state writeback failed",
                    toJsonSafe(Map.of("error", ex.getMessage() == null ? "" : ex.getMessage()))
            );
        }
    }

    private String resolveLatestToolRawRefFromChannel(Map<String, Object> rawToolResultChannel, ToolState previousToolState) {
        String channelRawRef = rawToolResultChannel == null ? "" : stringValue(rawToolResultChannel.get("latestToolRawRef"));
        if (!channelRawRef.isBlank()) {
            return channelRawRef;
        }
        return previousToolState == null ? "" : nullSafe(previousToolState.getLastToolRawResultRef());
    }

    private List<String> resolveImmediateToolHistoryRefs(List<String> explicitHistoryRefs,
                                                         Map<String, Object> rawToolResultChannel,
                                                         ToolState previousToolState) {
        return mergeDistinctList(
                previousToolState == null ? List.of() : previousToolState.getToolCallHistoryRefs(),
                mergeDistinct(
                        explicitHistoryRefs == null ? List.of() : explicitHistoryRefs,
                        extractToolRefsFromRawChannel(rawToolResultChannel)
                )
        );
    }

    private ModelReply invokeMainModel(String prompt,
                                       String repairSeed,
                                       StructuredContextPackage contextPackage,
                                       String sessionId,
                                       Long roundId,
                                       Long nodeId,
                                       String snapshotId) {
        String executionModelName = resolveExecutionModelName(contextPackage);
        LlmRequest request = LlmRequest.builder()
                .modelType(org.yilena.luna.enums.ModelType.OPENAI_COMPATIBLE)
                .modelName(executionModelName)
                .messages(List.of(LlmMessage.user(prompt)))
                .enablePromptInjectionCheck(true)
                .build();
        LlmResponse response = llmClientUtil.generate(request);
        String valid = response == null ? null : response.getContent();
        if (valid == null) {
            String fallback = createFallbackJson();
            return new ModelReply(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }
        JsonNode node = tryParseJsonNode(valid);
        if (!isValidReplyNode(node)) {
            try {
                PromptResolveResult repairResolved = resolveRepairPromptResult(repairSeed, contextPackage);
                persistRepairSnapshotRefs(sessionId, roundId, nodeId, snapshotId, repairResolved);
                String repairTemplate = resolveRepairPromptTemplate(repairResolved, contextPackage);
                String repairPrompt = repairTemplate.formatted(
                        repairSeed == null || repairSeed.isBlank() ? valid : repairSeed
                );
                LlmRequest repairRequest = LlmRequest.builder()
                        .modelType(org.yilena.luna.enums.ModelType.OPENAI_COMPATIBLE)
                        .modelName(executionModelName)
                        .messages(List.of(LlmMessage.user(repairPrompt)))
                        .enablePromptInjectionCheck(false)
                        .build();
                LlmResponse repairedResponse = llmClientUtil.generate(repairRequest);
                String repairedText = repairedResponse == null ? null : repairedResponse.getContent();
                if (repairedText != null) {
                    JsonNode repairedNode = tryParseJsonNode(repairedText);
                    if (isValidReplyNode(repairedNode)) {
                        String raw = repairedNode.toString();
                        return new ModelReply(raw, removeThoughtFromJson(raw), repairedNode.get(ModelHintConstant.REPLY).asText());
                    }
                }
            } catch (Exception ignore) {
            }
            String fallback = createFallbackJson();
            return new ModelReply(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }
        String raw = node.toString();
        return new ModelReply(raw, removeThoughtFromJson(raw), node.get(ModelHintConstant.REPLY).asText());
    }

    private PromptResolveResult resolveMainModelPromptAssembly(String userInput,
                                                               StructuredContextPackage contextPackage,
                                                               ContextNodeTemplatePolicy nodeTemplatePolicy) {
        if (promptResolverService == null) {
            return null;
        }
        try {
            PromptResolveContext context = PromptResolveContext.builder()
                    .sessionId(contextPackage == null ? "" : nullSafe(contextPackage.getSessionId()))
                    .userInput(userInput)
                    .policyId(resolvePromptPolicyId(contextPackage))
                    .manualPromptKeys(resolvePromptManualKeys(contextPackage))
                    .personaId(resolvePromptBinding(contextPackage, "personaId", "persona_id"))
                    .sceneId(resolvePromptBinding(contextPackage, "sceneId", "scene_id"))
                    .agent(nodeTemplatePolicy == null || nodeTemplatePolicy.getPromptAgent() == null || nodeTemplatePolicy.getPromptAgent().isBlank()
                            ? "MAIN_CHAT_AGENT"
                            : nodeTemplatePolicy.getPromptAgent())
                    .nodeKind(nodeTemplatePolicy == null || nodeTemplatePolicy.getNodeKind() == null || nodeTemplatePolicy.getNodeKind().isBlank()
                            ? "CHAT_TURN"
                            : nodeTemplatePolicy.getNodeKind())
                    .taskState(contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name())
                    .modelFamily(resolvePromptModelFamily(contextPackage))
                    .build();
            return promptResolverService.resolve(context);
        } catch (Exception ignore) {
            return null;
        }
    }

    private PromptResolveResult resolveRepairPromptResult(String repairSeed, StructuredContextPackage contextPackage) {
        if (promptResolverService != null) {
            try {
                return promptResolverService.resolve(PromptResolveContext.builder()
                        .sessionId(contextPackage == null ? "" : nullSafe(contextPackage.getSessionId()))
                        .userInput(repairSeed)
                        .policyId(resolvePromptPolicyId(contextPackage))
                        .manualPromptKeys(resolvePromptManualKeys(contextPackage))
                        .personaId(resolvePromptBinding(contextPackage, "personaId", "persona_id"))
                        .sceneId(resolvePromptBinding(contextPackage, "sceneId", "scene_id"))
                        .agent("MAIN_MODEL_REPAIR_AGENT")
                        .nodeKind("CHAT_TURN")
                        .taskState(contextPackage == null || contextPackage.getTaskState() == null ? "" : contextPackage.getTaskState().name())
                        .modelFamily(resolvePromptModelFamily(contextPackage))
                        .build());
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private String resolveRepairPromptTemplate(PromptResolveResult resolved,
                                               StructuredContextPackage contextPackage) {
        if (resolved != null) {
            try {
                String fromSlot = resolvePromptValueFromSlot(resolved, "repair.main");
                if (!fromSlot.isBlank()) {
                    return fromSlot;
                }
                String fromKey = resolvePromptValueFromKey(resolved, "repair.main.json_v1");
                if (!fromKey.isBlank()) {
                    return fromKey;
                }
            } catch (Exception ignore) {
            }
        }
        return promptRegistryService == null
                ? PromptTemplates.REPAIR_PROMPT
                : promptRegistryService.resolvePromptValue("repair.main.json_v1", PromptTemplates.REPAIR_PROMPT);
    }

    private void persistRepairSnapshotRefs(String sessionId,
                                           Long roundId,
                                           Long nodeId,
                                           String snapshotId,
                                           PromptResolveResult resolved) {
        if (promptSnapshotBridgeService == null
                || resolved == null
                || sessionId == null
                || sessionId.isBlank()
                || snapshotId == null
                || snapshotId.isBlank()) {
            return;
        }
        List<ResolvedPromptItem> slotItems = resolved.getSlotMapping() == null
                ? List.of()
                : resolved.getSlotMapping().getOrDefault("repair.main", List.of());
        List<ResolvedPromptItem> repairItems = new ArrayList<>();
        if (slotItems != null && !slotItems.isEmpty()) {
            repairItems.addAll(slotItems);
        } else if (resolved.getMatchedItems() != null) {
            for (ResolvedPromptItem item : resolved.getMatchedItems()) {
                if (isRepairMainPromptItem(item)) {
                    repairItems.add(item);
                }
            }
        }
        if (repairItems.isEmpty()) {
            return;
        }
        PromptResolveResult repairOnly = PromptResolveResult.builder()
                .policyId(resolved.getPolicyId())
                .matchedItems(repairItems)
                .slotMapping(Map.of("repair.main", repairItems))
                .build();
        Map<String, Object> payload = promptSnapshotBridgeService.buildSnapshotPayload(repairOnly, resolved.getPolicyId());
        promptSnapshotBridgeService.persistSnapshotRefs(sessionId, roundId, nodeId, snapshotId, payload);
    }

    private boolean isRepairMainPromptItem(ResolvedPromptItem item) {
        if (item == null) {
            return false;
        }
        String runtimeSlot = item.getRuntimeSlot();
        if (runtimeSlot != null && runtimeSlot.equalsIgnoreCase("repair.main")) {
            return true;
        }
        String key = item.getKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("repair.main.") || normalized.startsWith("repair.main_");
    }

    private String resolvePromptValueFromSlot(PromptResolveResult resolved, String slot) {
        if (resolved == null || resolved.getSlotMapping() == null || slot == null || slot.isBlank()) {
            return "";
        }
        List<ResolvedPromptItem> items = resolved.getSlotMapping().get(slot);
        if (items == null || items.isEmpty()) {
            return "";
        }
        for (ResolvedPromptItem item : items) {
            if (item != null && item.getValue() != null && !item.getValue().isBlank()) {
                return item.getValue();
            }
        }
        return "";
    }

    private String resolvePromptValueFromKey(PromptResolveResult resolved, String key) {
        if (resolved == null || resolved.getMatchedItems() == null || key == null || key.isBlank()) {
            return "";
        }
        for (ResolvedPromptItem item : resolved.getMatchedItems()) {
            if (item == null) {
                continue;
            }
            if (PromptKeyAliasSupport.matches(key, item.getKey()) && item.getValue() != null && !item.getValue().isBlank()) {
                return item.getValue();
            }
        }
        return "";
    }

    private String resolvePromptPolicyId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getPromptPolicy() == null) {
            return "";
        }
        Object byCamel = contextPackage.getPromptPolicy().get("policyId");
        if (byCamel != null && !String.valueOf(byCamel).isBlank()) {
            return String.valueOf(byCamel);
        }
        Object bySnake = contextPackage.getPromptPolicy().get("policy_id");
        return bySnake == null ? "" : String.valueOf(bySnake);
    }

    private List<String> resolvePromptManualKeys(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return List.of();
        }
        List<String> fromPolicy = readPromptKeyList(contextPackage.getPromptPolicy(), "manualPromptKeys", "manual_prompt_keys");
        if (!fromPolicy.isEmpty()) {
            return fromPolicy;
        }
        return readPromptKeyList(contextPackage.getTaskContext(), "manualPromptKeys", "manual_prompt_keys");
    }

    private String resolvePromptBinding(StructuredContextPackage contextPackage, String camelKey, String snakeKey) {
        if (contextPackage == null) {
            return "";
        }
        String fromPolicy = readPromptBindingMap(contextPackage.getPromptPolicy(), camelKey, snakeKey);
        if (!fromPolicy.isBlank()) {
            return fromPolicy;
        }
        String fromTask = readPromptBindingMap(contextPackage.getTaskContext(), camelKey, snakeKey);
        if (!fromTask.isBlank()) {
            return fromTask;
        }
        return readPromptBindingMap(contextPackage.getRelationalContext(), camelKey, snakeKey);
    }

    private String readPromptBindingMap(Map<String, Object> source, String camelKey, String snakeKey) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        Object byCamel = source.get(camelKey);
        if (byCamel != null && !String.valueOf(byCamel).isBlank()) {
            return String.valueOf(byCamel);
        }
        Object bySnake = source.get(snakeKey);
        return bySnake == null ? "" : String.valueOf(bySnake);
    }

    private List<String> readPromptKeyList(Map<String, Object> source, String camelKey, String snakeKey) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> byCamel = toPromptKeyList(source.get(camelKey));
        if (!byCamel.isEmpty()) {
            return byCamel;
        }
        return toPromptKeyList(source.get(snakeKey));
    }

    private List<String> toPromptKeyList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String key = String.valueOf(item).trim();
                if (!key.isBlank() && !values.contains(key)) {
                    values.add(key);
                }
            }
            return values;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace('\r', '\n');
        for (String part : normalized.split("[,\\n]")) {
            String key = part == null ? "" : part.trim();
            if (!key.isBlank() && !values.contains(key)) {
                values.add(key);
            }
        }
        return values;
    }

    private String resolvePromptModelFamily(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return "";
        }
        Object model = contextPackage.getRuntime().get("modelFamily");
        if (model != null && !String.valueOf(model).isBlank()) {
            return String.valueOf(model);
        }
        model = contextPackage.getRuntime().get("model_family");
        return model == null ? "" : String.valueOf(model);
    }

    private String resolveExecutionModelName(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return geminiProperty.getBig().getModelName();
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        org.yilena.luna.enums.RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING || taskState == TaskRuntimeState.EXECUTING)
                && geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null) {
            return geminiProperty.getCode().getModelName();
        }
        if ((relationalState == org.yilena.luna.enums.RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == org.yilena.luna.enums.RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == org.yilena.luna.enums.RelationalRuntimeState.REPAIRING)
                && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
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
        } catch (Exception ignore) {
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

    private RetrievalResponse mergeRetrievalResponses(RetrievalResponse primary, RetrievalResponse memoryOnly) {
        if (primary == null && memoryOnly == null) {
            return RetrievalResponse.builder().route(RetrievalRoute.SEARCH).rewrittenQuery("").evidences(Map.of()).meta(Map.of()).build();
        }
        if (primary == null) {
            return memoryOnly;
        }
        if (memoryOnly == null) {
            return primary;
        }
        Map<RetrievalSource, List<Evidence>> mergedEvidences = new LinkedHashMap<>();
        for (RetrievalSource source : RetrievalSource.values()) {
            List<Evidence> left = getEvidences(primary, source);
            List<Evidence> right = getEvidences(memoryOnly, source);
            List<Evidence> merged = new ArrayList<>();
            merged.addAll(left);
            merged.addAll(right);
            mergedEvidences.put(source, merged.stream().distinct().toList());
        }
        Map<String, Object> mergedMeta = new LinkedHashMap<>();
        if (primary.getMeta() != null) {
            mergedMeta.putAll(primary.getMeta());
        }
        if (memoryOnly.getMeta() != null) {
            mergedMeta.put("memory_meta", memoryOnly.getMeta());
        }
        return RetrievalResponse.builder()
                .route(primary.getRoute())
                .rewrittenQuery(primary.getRewrittenQuery())
                .evidences(mergedEvidences)
                .evidenceRoleGroups(primary.getEvidenceRoleGroups() == null ? Map.of() : primary.getEvidenceRoleGroups())
                .meta(mergedMeta)
                .build();
    }

    private String toEvidenceSnippet(EvidenceBlock block) {
        if (block == null) {
            return "";
        }
        return "id=" + nullSafe(block.getBlockId())
                + "; source=" + nullSafe(block.getSourceType())
                + "; score=" + nullSafe(block.getScore() == null ? "" : String.valueOf(block.getScore()))
                + "; title=" + nullSafe(block.getTitle())
                + "; content=" + nullSafe(block.getContent());
    }

    private RecoveryRefreshPlan consumeRecoveryRefreshPlan(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRetrievalState() == null) {
            return RecoveryRefreshPlan.empty();
        }
        Map<String, Object> retrievalPlan = contextPackage.getRetrievalState().getRetrievalPlan();
        if (retrievalPlan == null || retrievalPlan.isEmpty()) {
            return RecoveryRefreshPlan.empty();
        }
        boolean refreshRagNow = booleanValue(retrievalPlan.get("refresh_rag_now"))
                || booleanValue(retrievalPlan.get("refreshRagNow"))
                || booleanValue(retrievalPlan.get("need_rag_refresh"));
        boolean refreshMcpNow = booleanValue(retrievalPlan.get("refresh_mcp_now"))
                || booleanValue(retrievalPlan.get("refreshMcpNow"))
                || booleanValue(retrievalPlan.get("need_mcp_refresh"));
        boolean reassembleNow = booleanValue(retrievalPlan.get("reassemble_now"))
                || booleanValue(retrievalPlan.get("reassembleNow"))
                || booleanValue(retrievalPlan.get("need_reassembly"));
        List<String> invalidatedEvidenceRefs = toStringList(retrievalPlan.get("invalidated_evidence_refs"));
        List<String> invalidatedCapabilityNames = toStringList(retrievalPlan.get("invalidated_capability_names"));
        Map<String, String> invalidationReasonsByRef = safeStringMap(retrievalPlan.get("invalidation_reasons_by_ref"));
        if (!refreshRagNow && !refreshMcpNow && !reassembleNow) {
            if ((invalidatedEvidenceRefs == null || invalidatedEvidenceRefs.isEmpty())
                    && (invalidatedCapabilityNames == null || invalidatedCapabilityNames.isEmpty())) {
                return RecoveryRefreshPlan.empty();
            }
        }
        Map<String, Object> consumed = new LinkedHashMap<>(retrievalPlan);
        consumed.put("refresh_rag_now", false);
        consumed.put("refresh_mcp_now", false);
        consumed.put("reassemble_now", false);
        consumed.put("refreshRagNow", false);
        consumed.put("refreshMcpNow", false);
        consumed.put("reassembleNow", false);
        consumed.put("need_rag_refresh", false);
        consumed.put("need_mcp_refresh", false);
        consumed.put("need_reassembly", false);
        consumed.put("invalidated_evidence_refs", List.of());
        consumed.put("invalidated_capability_names", List.of());
        consumed.put("invalidation_reasons_by_ref", Map.of());
        contextPackage.setRetrievalState(org.yilena.luna.state.model.RetrievalState.builder()
                .reconstructedIntent(contextPackage.getRetrievalState().getReconstructedIntent())
                .activeQueries(contextPackage.getRetrievalState().getActiveQueries())
                .retrievalPlan(consumed)
                .selectedEvidenceRefs(contextPackage.getRetrievalState().getSelectedEvidenceRefs())
                .rerankSummary(contextPackage.getRetrievalState().getRerankSummary())
                .build());
        recoveryStateStore.clear(contextPackage.getSessionId());
        return new RecoveryRefreshPlan(
                refreshRagNow,
                refreshMcpNow,
                reassembleNow,
                invalidatedEvidenceRefs == null ? List.of() : invalidatedEvidenceRefs,
                invalidatedCapabilityNames == null ? List.of() : invalidatedCapabilityNames,
                invalidationReasonsByRef == null ? Map.of() : invalidationReasonsByRef
        );
    }

    /**
     * 判断是否需要在恢复流程中立即执行上下文刷新操作。
     *
     * <p>该方法检查检索计划中的刷新标记，确认是否需要立即执行以下操作之一：</p>
     * <ul>
     *   <li>RAG检索刷新：更新过期的知识检索结果</li>
     *   <li>MCP工具刷新：重新执行失效的工具调用</li>
     *   <li>上下文重组：重新组装因状态变化而失效的上下文</li>
     * </ul>
     *
     * @param contextPackage 结构化上下文包，提供检索计划和当前状态信息
     * @param reconstructionResult 输入重构结果，确保在有完整意图理解时才执行刷新
     * @return boolean 是否需要立即执行刷新操作，返回true表示：
     *         - 检索计划中存在有效的刷新标记（RAG/MCP/重组）
     *         - 且输入重构结果可用，保证刷新基于正确的意图
     */
    private boolean shouldRunImmediateRecoveryRefresh(StructuredContextPackage contextPackage,
                                                      InputReconstructionResult reconstructionResult) {
        // 基础校验：上下文包和检索状态必须存在
        if (contextPackage == null || contextPackage.getRetrievalState() == null) {
            return false;
        }

        // 提取检索计划，为空则无需刷新
        Map<String, Object> plan = contextPackage.getRetrievalState().getRetrievalPlan();
        if (plan == null || plan.isEmpty()) {
            return false;
        }

        // 检测三类刷新需求（兼容多种命名风格）
        boolean refreshRagNow = booleanValue(plan.get("refresh_rag_now"))
                || booleanValue(plan.get("refreshRagNow"))
                || booleanValue(plan.get("need_rag_refresh"));
        boolean refreshMcpNow = booleanValue(plan.get("refresh_mcp_now"))
                || booleanValue(plan.get("refreshMcpNow"))
                || booleanValue(plan.get("need_mcp_refresh"));
        boolean reassembleNow = booleanValue(plan.get("reassemble_now"))
                || booleanValue(plan.get("reassembleNow"))
                || booleanValue(plan.get("need_reassembly"));

        // 如无任何刷新需求，直接返回false
        if (!(refreshRagNow || refreshMcpNow || reassembleNow)) {
            return false;
        }

        // 确保有完整的意图重构结果才执行刷新，避免基于错误意图操作
        return reconstructionResult != null;
    }


    private StructuredContextPackage applyImmediateRecoveryRefreshResult(StructuredContextPackage contextPackage,
                                                                         InputReconstructionResult reconstructionResult,
                                                                         NodeWorksetResult nodeWorksetResult) {
        if (contextPackage == null || nodeWorksetResult == null) {
            return contextPackage;
        }
        RetrievalState baseRetrieval = contextPackage.getRetrievalState();
        Map<String, Object> basePlan = baseRetrieval == null || baseRetrieval.getRetrievalPlan() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(baseRetrieval.getRetrievalPlan());
        basePlan.put("immediate_refresh_executed", true);
        basePlan.put("immediate_refresh_at", System.currentTimeMillis());
        basePlan.put("invalidated_evidence_refs", nodeWorksetResult.getInvalidatedEvidenceRefs() == null ? List.of() : nodeWorksetResult.getInvalidatedEvidenceRefs());
        basePlan.put("invalidated_capability_names", nodeWorksetResult.getInvalidatedCapabilityNames() == null ? List.of() : nodeWorksetResult.getInvalidatedCapabilityNames());

        RetrievalState refreshedRetrievalState = RetrievalState.builder()
                .reconstructedIntent(reconstructionResult == null ? nullSafe(baseRetrieval == null ? "" : baseRetrieval.getReconstructedIntent()) : reconstructionResult.getNormalizedUserIntent())
                .activeQueries(mergeDistinctList(
                        baseRetrieval == null ? List.of() : baseRetrieval.getActiveQueries(),
                        mergeDistinct(
                                mergeDistinct(nonBlankList(nodeWorksetResult.getRagQuery()), nonBlankList(nodeWorksetResult.getMemoryQuery())),
                                nonBlankList(nodeWorksetResult.getMcpDrivenInput())
                        )
                ))
                .retrievalPlan(basePlan)
                .selectedEvidenceRefs(nodeWorksetResult.getSelectedKnowledgeEvidenceRefs() == null ? List.of() : nodeWorksetResult.getSelectedKnowledgeEvidenceRefs())
                .rerankSummary(toJsonSafe(nodeWorksetResult.getRerankRationaleByNode() == null ? Map.of() : nodeWorksetResult.getRerankRationaleByNode()))
                .build();
        retrievalStateStore.save(contextPackage.getSessionId(), refreshedRetrievalState);

        ContextState baseContextState = contextPackage.getContextState();
        ContextRerankResult rerankResult = nodeWorksetResult.getRerankResult();
        List<String> refreshedPromptRefs = firstNonEmpty(
                nodeWorksetResult.getSelectedPromptCandidateNames(),
                nodeWorksetResult.getSelectedPromptResourceNames()
        );
        List<String> refreshedResourceRefs = firstNonEmpty(
                nodeWorksetResult.getSelectedResourceCandidateNames(),
                baseContextState == null ? List.of() : baseContextState.getActiveMcpResourceRefs()
        );
        List<String> refreshedWorkflowRefs = firstNonEmpty(
                nodeWorksetResult.getSelectedWorkflowCandidateNames(),
                baseContextState == null ? List.of() : baseContextState.getActiveMcpWorkflowRefs()
        );
        List<String> refreshedToolRefs = firstNonEmpty(
                nodeWorksetResult.getSelectedMcpToolCandidateNames(),
                nodeWorksetResult.getSelectedToolCandidateNames(),
                baseContextState == null ? List.of() : resolveLegacyMcpToolRefs(baseContextState)
        );
        Map<String, Object> refreshedLatestSnapshot = baseContextState == null ? new LinkedHashMap<>() : new LinkedHashMap<>(safeMap(baseContextState.getLatestStateSnapshot()));
        refreshedLatestSnapshot.put("activeMcpPromptRefs", refreshedPromptRefs);
        refreshedLatestSnapshot.put("activeMcpResourceRefs", refreshedResourceRefs);
        refreshedLatestSnapshot.put("activeMcpWorkflowRefs", refreshedWorkflowRefs);
        refreshedLatestSnapshot.put("activeMcpToolRefs", refreshedToolRefs);
        refreshedLatestSnapshot.put("activeMcpResourceRefsLegacy", mergeDistinct(refreshedResourceRefs, refreshedToolRefs));
        ContextState refreshedContextState = ContextState.builder()
                .latestNarrativeSummary(baseContextState == null ? "" : nullSafe(baseContextState.getLatestNarrativeSummary()))
                .latestStateSnapshot(refreshedLatestSnapshot)
                .activeKnowledgeRefs(nodeWorksetResult.getSelectedKnowledgeEvidenceRefs() == null ? List.of() : nodeWorksetResult.getSelectedKnowledgeEvidenceRefs())
                .activeMemoryRefs(rerankResult == null || rerankResult.getSelectedMemoryHints() == null
                        ? (baseContextState == null ? List.of() : toStringList(baseContextState.getActiveMemoryRefs()))
                        : rerankResult.getSelectedMemoryHints())
                .activeToolEvidenceRefs(baseContextState == null ? List.of() : toStringList(baseContextState.getActiveToolEvidenceRefs()))
                .activeMcpPromptRefs(refreshedPromptRefs)
                .activeMcpResourceRefs(refreshedResourceRefs)
                .activeMcpWorkflowRefs(refreshedWorkflowRefs)
                .activeMcpToolRefs(refreshedToolRefs)
                .latestContextSnapshotId(baseContextState == null ? "" : nullSafe(baseContextState.getLatestContextSnapshotId()))
                .build();
        contextStateStore.save(contextPackage.getSessionId(), refreshedContextState);

        contextPackage.setRetrievalState(refreshedRetrievalState);
        contextPackage.setContextState(refreshedContextState);
        return contextPackage;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean hasPendingRecoveryWork(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return false;
        }
        Map<String, Object> promptPolicy = contextPackage.getPromptPolicy();
        if (promptPolicy != null && booleanValue(promptPolicy.get("recovery_required"))) {
            return true;
        }
        if (contextPackage.getRetrievalState() == null || contextPackage.getRetrievalState().getRetrievalPlan() == null) {
            return false;
        }
        Map<String, Object> retrievalPlan = contextPackage.getRetrievalState().getRetrievalPlan();
        return booleanValue(retrievalPlan.get("need_rag_refresh"))
                || booleanValue(retrievalPlan.get("need_mcp_refresh"))
                || booleanValue(retrievalPlan.get("need_reassembly"))
                || booleanValue(retrievalPlan.get("refresh_rag_now"))
                || booleanValue(retrievalPlan.get("refresh_mcp_now"))
                || booleanValue(retrievalPlan.get("reassemble_now"))
                || booleanValue(retrievalPlan.get("refreshRagNow"))
                || booleanValue(retrievalPlan.get("refreshMcpNow"))
                || booleanValue(retrievalPlan.get("reassembleNow"));
    }

    private String appendRefreshFlag(String query, String source) {
        String base = nullSafe(query).trim();
        if (base.isBlank()) {
            base = "recovery refresh";
        }
        return base + " [recovery_refresh=" + source + "]";
    }

    private String buildTraceId(String traceLayer, String sessionId, Long planId, Long nodeId) {
        return (traceLayer == null ? "TRACE" : traceLayer)
                + ":" + nullSafe(sessionId)
                + ":" + (planId == null ? "0" : planId)
                + ":" + (nodeId == null ? "0" : nodeId)
                + ":" + System.currentTimeMillis();
    }

    private Map<String, Object> buildTraceMeta(StructuredContextPackage contextPackage,
                                               Long nodeId,
                                               String traceId,
                                               String traceLayer) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("traceId", nullSafe(traceId));
        meta.put("traceLayer", nullSafe(traceLayer));
        meta.put("nodeId", nodeId == null ? "" : String.valueOf(nodeId));
        String snapshotId = "";
        if (contextPackage != null && contextPackage.getContextState() != null) {
            snapshotId = nullSafe(contextPackage.getContextState().getLatestContextSnapshotId());
        }
        meta.put("snapshotId", snapshotId);
        String recoveryEvent = "";
        if (contextPackage != null && contextPackage.getRecoveryState() != null) {
            recoveryEvent = nullSafe(contextPackage.getRecoveryState().getRecoveryEvent());
        }
        meta.put("recoveryEvent", recoveryEvent);
        return meta;
    }

    private Map<String, Object> withTraceMeta(Map<String, Object> payload,
                                              Map<String, Object> traceMeta,
                                              String traceLayer,
                                              Long nodeId) {
        Map<String, Object> merged = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        merged.put("traceId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("traceId", "")));
        merged.put("traceLayer", nullSafe(traceLayer));
        merged.put("nodeId", nodeId == null ? "" : String.valueOf(nodeId));
        merged.put("snapshotId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("snapshotId", "")));
        merged.put("recoveryEvent", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("recoveryEvent", "")));
        return merged;
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ReconstructionRecallGate evaluateReconstructionRecallGate(InputReconstructionResult reconstructionResult,
                                                                      TaskRuntimeState runtimeState) {
        if (reconstructionResult == null) {
            return new ReconstructionRecallGate(
                    false,
                    "input_reconstruction_missing",
                    0.0d,
                    RECALL_MIN_CONFIDENCE_LIGHT,
                    0,
                    RECALL_MAX_MISSING_SLOTS_LIGHT,
                    0,
                    0
            );
        }
        if (!nonBlank(reconstructionResult.getExplicitTaskGoal())) {
            return new ReconstructionRecallGate(
                    false,
                    "input_reconstruction_goal_missing",
                    reconstructionResult.getIntentConfidence(),
                    RECALL_MIN_CONFIDENCE_LIGHT,
                    countMissingSlots(reconstructionResult),
                    RECALL_MAX_MISSING_SLOTS_LIGHT,
                    0,
                    countRequiredEntities(reconstructionResult)
            );
        }

        RecallThreshold threshold = resolveRecallThreshold(runtimeState);
        double confidence = reconstructionResult.getIntentConfidence();
        int missingSlots = countMissingSlots(reconstructionResult);
        int entityCount = countRequiredEntities(reconstructionResult);

        if (confidence < threshold.minIntentConfidence()) {
            return new ReconstructionRecallGate(
                    false,
                    "input_reconstruction_confidence_low",
                    confidence,
                    threshold.minIntentConfidence(),
                    missingSlots,
                    threshold.maxMissingSlots(),
                    threshold.requiredEntities(),
                    entityCount
            );
        }
        if (missingSlots > threshold.maxMissingSlots()) {
            return new ReconstructionRecallGate(
                    false,
                    "input_reconstruction_missing_slots_exceeded",
                    confidence,
                    threshold.minIntentConfidence(),
                    missingSlots,
                    threshold.maxMissingSlots(),
                    threshold.requiredEntities(),
                    entityCount
            );
        }
        if (entityCount < threshold.requiredEntities()) {
            return new ReconstructionRecallGate(
                    false,
                    "input_reconstruction_required_entities_missing",
                    confidence,
                    threshold.minIntentConfidence(),
                    missingSlots,
                    threshold.maxMissingSlots(),
                    threshold.requiredEntities(),
                    entityCount
            );
        }
        return new ReconstructionRecallGate(
                true,
                "",
                confidence,
                threshold.minIntentConfidence(),
                missingSlots,
                threshold.maxMissingSlots(),
                threshold.requiredEntities(),
                entityCount
        );
    }

    private RecallThreshold resolveRecallThreshold(TaskRuntimeState runtimeState) {
        if (runtimeState == null) {
            return new RecallThreshold(RECALL_MIN_CONFIDENCE_LIGHT, RECALL_MAX_MISSING_SLOTS_LIGHT, 0);
        }
        return switch (runtimeState) {
            case EXECUTING, WAITING_TOOL, WAITING_APPROVAL, REPORTING, REPLANNING, REFLECTING ->
                    new RecallThreshold(RECALL_MIN_CONFIDENCE_EXECUTION, RECALL_MAX_MISSING_SLOTS_EXECUTION, 1);
            case CONTEXT_BUILDING, PLANNING, WAITING_PLAN_CONFIRMATION ->
                    new RecallThreshold(RECALL_MIN_CONFIDENCE_PLANNING, RECALL_MAX_MISSING_SLOTS_PLANNING, 1);
            default -> new RecallThreshold(RECALL_MIN_CONFIDENCE_LIGHT, RECALL_MAX_MISSING_SLOTS_LIGHT, 0);
        };
    }

    private int countMissingSlots(InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null || reconstructionResult.getMissingSlots() == null) {
            return 0;
        }
        return (int) reconstructionResult.getMissingSlots().stream()
                .filter(this::nonBlank)
                .count();
    }

    private int countRequiredEntities(InputReconstructionResult reconstructionResult) {
        if (reconstructionResult == null || reconstructionResult.getClarifiedEntities() == null) {
            return 0;
        }
        return (int) reconstructionResult.getClarifiedEntities().entrySet().stream()
                .filter(entry -> nonBlank(entry.getKey()) && nonBlank(entry.getValue()))
                .count();
    }

    /**
     * 召回阈值配置，定义输入重构结果进入后续流程所需满足的门槛。
     */
    private record RecallThreshold(double minIntentConfidence, int maxMissingSlots, int requiredEntities) {
    }

    /**
     * 重构召回门控结果，记录当前重构结果是否满足证据召回前置条件。
     */
    private record ReconstructionRecallGate(boolean ready,
                                            String blockedReason,
                                            double intentConfidence,
                                            double minIntentConfidence,
                                            int missingSlots,
                                            int maxMissingSlots,
                                            int requiredEntities,
                                            int entityCount) {
    }

    private NodeWorksetResult blockedNodeWorksetResult(String reason) {
        return NodeWorksetResult.builder()
                .mcpDrivenInput("")
                .ragQuery("")
                .memoryQuery("")
                .mcpPreRankedCandidates(List.of())
                .rerankResult(null)
                .rerankRationaleByNode(Map.of("blocked_reason", nullSafe(reason)))
                .selectedKnowledgeEvidenceBlocks(List.of())
                .selectedKnowledgeEvidenceRefs(List.of())
                .selectedKnowledgeSnippets(List.of())
                .selectedMemorySnippets(List.of())
                .selectedPreferenceSnippets(List.of())
                .selectedToolCandidateNames(List.of())
                .selectedMcpToolCandidateNames(List.of())
                .selectedPromptCandidateNames(List.of())
                .selectedResourceCandidateNames(List.of())
                .selectedWorkflowCandidateNames(List.of())
                .selectedPromptResourceNames(List.of())
                .invalidatedEvidenceRefs(List.of())
                .invalidatedCapabilityNames(List.of())
                .invalidationReasonsByRef(Map.of())
                .executionCandidates(List.of())
                .mcpResourceHints(List.of())
                .build();
    }

    private void persistReconstructionBlockedState(String sessionId,
                                                   OrchestrationDecision decision,
                                                   StructuredContextPackage contextPackage,
                                                   InputReconstructionResult reconstructionResult,
                                                   String reason) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            RetrievalState previousRetrieval = contextPackage == null ? null : contextPackage.getRetrievalState();
            Map<String, Object> blockedPlan = new LinkedHashMap<>();
            if (previousRetrieval != null && previousRetrieval.getRetrievalPlan() != null) {
                blockedPlan.putAll(previousRetrieval.getRetrievalPlan());
            }
            blockedPlan.put("blocked", true);
            blockedPlan.put("blocked_reason", reason == null ? "reconstruction_missing" : reason);
            blockedPlan.put("blocked_stage", "NODE_WORKSET");
            blockedPlan.put("blocked_by_reconstruction", true);
            blockedPlan.put("refresh_rag_now", false);
            blockedPlan.put("refresh_mcp_now", false);
            blockedPlan.put("reassemble_now", false);
            blockedPlan.put("refreshRagNow", false);
            blockedPlan.put("refreshMcpNow", false);
            blockedPlan.put("reassembleNow", false);
            blockedPlan.put("need_rag_refresh", false);
            blockedPlan.put("need_mcp_refresh", false);
            blockedPlan.put("need_reassembly", false);
            blockedPlan.put("invalidated_evidence_refs", List.of());
            blockedPlan.put("invalidated_capability_names", List.of());
            blockedPlan.put("invalidation_reasons_by_ref", Map.of());
            RetrievalState blockedRetrieval = RetrievalState.builder()
                    .reconstructedIntent(reconstructionResult == null ? "" : nullSafe(reconstructionResult.getNormalizedUserIntent()))
                    .activeQueries(mergeDistinctList(
                            previousRetrieval == null ? List.of() : previousRetrieval.getActiveQueries(),
                            List.of("BLOCKED:" + nullSafe(reason))
                    ))
                    .retrievalPlan(blockedPlan)
                    .selectedEvidenceRefs(previousRetrieval == null ? List.of() : previousRetrieval.getSelectedEvidenceRefs())
                    .rerankSummary(previousRetrieval == null ? "" : nullSafe(previousRetrieval.getRerankSummary()))
                    .build();
            retrievalStateStore.save(sessionId, blockedRetrieval);

            TaskState previousTask = contextPackage == null ? null : contextPackage.getTaskStateEntity();
            TaskState blockedTask = TaskState.builder()
                    .taskId(previousTask == null ? String.valueOf(contextPlanId(contextPackage)) : nullSafe(previousTask.getTaskId()))
                    .sessionId(sessionId)
                    .objective(reconstructionResult == null ? "" : nullSafe(reconstructionResult.getExplicitTaskGoal()))
                    .currentStage(decision == null || decision.getTaskState() == null ? "UNKNOWN" : decision.getTaskState().name())
                    .currentNode(previousTask == null ? String.valueOf(contextNodeId(contextPackage)) : nullSafe(previousTask.getCurrentNode()))
                    .confirmedSlots(previousTask == null ? Map.of() : safeMap(previousTask.getConfirmedSlots()))
                    .pendingQuestions(previousTask == null ? List.of() : toStringList(previousTask.getPendingQuestions()))
                    .finishedSteps(previousTask == null ? List.of() : toStringList(previousTask.getFinishedSteps()))
                    .failedSteps(mergeDistinctList(previousTask == null ? List.of() : previousTask.getFailedSteps(), List.of("NODE_WORKSET_BLOCKED")))
                    .retryCount(previousTask == null ? 0 : previousTask.getRetryCount())
                    .nextActionHint("reconstruct_input_then_retry")
                    .build();
            taskStateStore.save(sessionId, blockedTask);
        } catch (Exception ignore) {
        }
    }

    private Map<String, Object> buildInputReconstructionAuditPayload(String rawInput,
                                                                     InputReconstructionResult reconstruction,
                                                                     StructuredContextPackage contextPackage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String raw = nullSafe(rawInput).trim();
        payload.put("raw_input", raw);
        payload.put("raw_input_length", raw.length());
        payload.put("reconstruction", reconstruction == null ? Map.of() : reconstruction);
        payload.put("delta", buildReconstructionDelta(raw, reconstruction, contextPackage));
        return payload;
    }

    private Map<String, Object> buildReconstructionDelta(String rawInput,
                                                         InputReconstructionResult reconstruction,
                                                         StructuredContextPackage contextPackage) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (reconstruction == null) {
            delta.put("status", "missing_reconstruction");
            delta.put("added_items", List.of());
            delta.put("disambiguated_items", List.of());
            delta.put("carried_from_snapshot", List.of());
            return delta;
        }
        String normalizedRaw = normalizeForCompare(rawInput);
        List<String> addedItems = new ArrayList<>();
        addIfNew(addedItems, "explicitTaskGoal", reconstruction.getExplicitTaskGoal(), normalizedRaw);
        addIfNew(addedItems, "normalizedUserIntent", reconstruction.getNormalizedUserIntent(), normalizedRaw);
        addIfNew(addedItems, "timeScope", reconstruction.getTimeScope(), normalizedRaw);
        addIfNew(addedItems, "ragQuery", reconstruction.getReformulatedQueryForRag(), normalizedRaw);
        addIfNew(addedItems, "mcpQuery", reconstruction.getReformulatedQueryForMcp(), normalizedRaw);
        if (reconstruction.getBusinessConstraints() != null) {
            for (String constraint : reconstruction.getBusinessConstraints()) {
                addIfNew(addedItems, "constraint", constraint, normalizedRaw);
            }
        }
        if (reconstruction.getMissingSlots() != null) {
            for (String slot : reconstruction.getMissingSlots()) {
                String cleaned = nullSafe(slot).trim();
                if (!cleaned.isBlank()) {
                    addedItems.add("missingSlot=" + cleaned);
                }
            }
        }

        List<String> disambiguatedItems = new ArrayList<>();
        Map<String, String> entities = reconstruction.getClarifiedEntities();
        if (entities != null) {
            for (Map.Entry<String, String> entry : entities.entrySet()) {
                String key = nullSafe(entry.getKey()).trim();
                String value = nullSafe(entry.getValue()).trim();
                if (key.isBlank() || value.isBlank()) {
                    continue;
                }
                if (!containsNormalized(normalizedRaw, value)) {
                    disambiguatedItems.add(key + "=" + value);
                }
            }
        }

        LinkedHashSet<String> dedupAdded = new LinkedHashSet<>(addedItems);
        LinkedHashSet<String> dedupDisambiguated = new LinkedHashSet<>(disambiguatedItems);
        List<String> carriedFromSnapshot = deriveCarriedFromSnapshot(rawInput, reconstruction, contextPackage);
        delta.put("status", (dedupAdded.isEmpty() && dedupDisambiguated.isEmpty()) ? "no_explicit_delta" : "delta_detected");
        delta.put("added_items", new ArrayList<>(dedupAdded));
        delta.put("disambiguated_items", new ArrayList<>(dedupDisambiguated));
        delta.put("carried_from_snapshot", carriedFromSnapshot == null ? List.of() : carriedFromSnapshot);
        delta.put("intent_confidence", reconstruction.getIntentConfidence());
        return delta;
    }

    @SuppressWarnings("unchecked")
    private List<String> deriveCarriedFromSnapshot(String rawInput,
                                                   InputReconstructionResult reconstruction,
                                                   StructuredContextPackage contextPackage) {
        if (reconstruction == null || contextPackage == null || contextPackage.getContextState() == null) {
            return List.of();
        }
        Map<String, Object> latestSnapshot = contextPackage.getContextState().getLatestStateSnapshot();
        if (latestSnapshot == null || latestSnapshot.isEmpty()) {
            return List.of();
        }
        String normalizedRaw = normalizeForCompare(rawInput);
        List<String> carried = new ArrayList<>();

        String snapshotTimeScope = stringValue(latestSnapshot.get("timeScope"));
        if (!snapshotTimeScope.isBlank()
                && snapshotTimeScope.equalsIgnoreCase(nullSafe(reconstruction.getTimeScope()))
                && !containsNormalized(normalizedRaw, snapshotTimeScope)) {
            carried.add("timeScope");
        }

        String snapshotNextAction = stringValue(latestSnapshot.get("nextStep"));
        if (!snapshotNextAction.isBlank() && !containsNormalized(normalizedRaw, snapshotNextAction)) {
            carried.add("nextActionHint");
        }

        Object unresolvedIssues = latestSnapshot.get("unresolvedIssues");
        if (unresolvedIssues instanceof List<?> unresolvedList) {
            List<String> unresolved = unresolvedList.stream()
                    .map(item -> item == null ? "" : String.valueOf(item))
                    .filter(item -> !item.isBlank())
                    .toList();
            if (!unresolved.isEmpty()) {
                boolean referencedByMissingSlots = reconstruction.getMissingSlots() != null
                        && reconstruction.getMissingSlots().stream()
                        .anyMatch(slot -> unresolved.stream().anyMatch(issue -> containsNormalized(normalizeForCompare(slot), issue)));
                boolean referencedByConstraints = reconstruction.getBusinessConstraints() != null
                        && reconstruction.getBusinessConstraints().stream()
                        .anyMatch(constraint -> unresolved.stream().anyMatch(issue -> containsNormalized(normalizeForCompare(constraint), issue)));
                if (referencedByMissingSlots || referencedByConstraints) {
                    carried.add("unfinishedActions");
                }
            }
        }

        return carried.stream().distinct().toList();
    }

    private void addIfNew(List<String> sink, String label, String value, String normalizedRaw) {
        String cleaned = nullSafe(value).trim();
        if (cleaned.isBlank()) {
            return;
        }
        if (!containsNormalized(normalizedRaw, cleaned)) {
            sink.add(label + "=" + cleaned);
        }
    }

    private boolean containsNormalized(String normalizedRaw, String value) {
        if (normalizedRaw == null || normalizedRaw.isBlank()) {
            return false;
        }
        String normalizedValue = normalizeForCompare(value);
        return !normalizedValue.isBlank() && normalizedRaw.contains(normalizedValue);
    }

    private String normalizeForCompare(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private GovernedSignal buildGovernedSignal(String rawInput, InputReconstructionResult reconstruction) {
        return GovernedSignal.fromReconstruction(rawInput, reconstruction);
    }

        /**
     * 解析恢复触发条件，判断当前会话是否需要执行恢复流程。
     *
     * <p>该方法从三个维度检测恢复信号：</p>
     * <ol>
     *   <li>状态标记：检查提示策略和检索计划中是否存在待处理的恢复标记</li>
     *   <li>用户意图：识别用户输入中的显式恢复/重试指令</li>
     *   <li>历史恢复：延续之前未完成的恢复事件</li>
     * </ol>
     *
     * @param input 用户原始输入文本，用于检测显式恢复指令（如"继续"、"重试"等）
     * @param decision 任务编排决策结果，提供当前任务状态和策略信息
     * @param contextPackage 结构化上下文包，包含提示策略、检索计划和历史恢复状态
     * @return RecoveryTrigger 恢复触发器对象，包含：
     *         - shouldRecover: 是否需要执行恢复流程
     *         - recoveryEvent: 恢复事件类型（如 RESUME_REQUEST、EXTERNAL_EVENT 等）
     *         - interruptReason: 中断/恢复原因说明
     */
    private RecoveryTrigger resolveRecoveryTrigger(String input,
                                                   OrchestrationDecision decision,
                                                   StructuredContextPackage contextPackage) {
        // 标准化用户输入以便关键词匹配
        String normalizedInput = nullSafe(input).trim().toLowerCase(Locale.ROOT);

        // 从决策结果或上下文包中获取当前任务状态
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        if (taskState == null && contextPackage != null) {
            taskState = contextPackage.getTaskState();
        }

        // 判断是否处于等待外部响应的状态（需人工审批、工具回调或用户确认）
        boolean waitingResumeState = taskState == TaskRuntimeState.WAITING_APPROVAL
                || taskState == TaskRuntimeState.WAITING_TOOL
                || taskState == TaskRuntimeState.WAITING_USER;

        // 提取提示策略和检索计划中的关键标记
        Map<String, Object> promptPolicy = contextPackage == null ? Map.of() : safeMap(contextPackage.getPromptPolicy());
        Map<String, Object> retrievalPlan = contextPackage == null || contextPackage.getRetrievalState() == null
                ? Map.of()
                : safeMap(contextPackage.getRetrievalState().getRetrievalPlan());

        // 检测1：基于状态标记的恢复需求（来自上游服务的刷新指令）
        boolean pendingRecoveryByState = booleanValue(promptPolicy.get("recovery_required"))
                || booleanValue(retrievalPlan.get("need_rag_refresh"))
                || booleanValue(retrievalPlan.get("need_mcp_refresh"))
                || booleanValue(retrievalPlan.get("need_reassembly"))
                || booleanValue(retrievalPlan.get("refresh_rag_now"))
                || booleanValue(retrievalPlan.get("refresh_mcp_now"))
                || booleanValue(retrievalPlan.get("reassemble_now"))
                || booleanValue(retrievalPlan.get("refreshRagNow"))
                || booleanValue(retrievalPlan.get("refreshMcpNow"))
                || booleanValue(retrievalPlan.get("reassembleNow"));

        // 如果存在状态级恢复标记，立即返回恢复触发器
        if (pendingRecoveryByState) {
            return new RecoveryTrigger(
                    true,
                    firstNonBlank(
                            stringValue(promptPolicy.get("recovery_event")),
                            firstNonBlank(
                                    contextPackage == null || contextPackage.getRecoveryState() == null ? "" : contextPackage.getRecoveryState().getRecoveryEvent(),
                                    "RECOVERY_STATE_PENDING"
                            )
                    ),
                    firstNonBlank(
                            stringValue(promptPolicy.get("recovery_reason")),
                            firstNonBlank(
                                    contextPackage == null || contextPackage.getRecoveryState() == null ? "" : contextPackage.getRecoveryState().getInterruptReason(),
                                    "RECOVERY_STATE_PENDING"
                            )
                    )
            );
        }

        // 检测2：基于用户输入的显式恢复指令
        boolean explicitResume = containsAny(normalizedInput,
                "resume", "continue", "批准", "通过", "恢复", "继续", "确认", "approve", "confirmed");
        boolean explicitRetry = containsAny(normalizedInput, "retry", "重试", "再试", "重新执行");
        boolean explicitInterruptEvent = containsAny(normalizedInput, "callback", "tool result", "审批结果", "approval result");

        // 在等待状态下识别用户的恢复/重试意图
        if (waitingResumeState && explicitResume) {
            return new RecoveryTrigger(true, "RESUME_REQUEST", "USER_RESUME_SIGNAL");
        }
        if (waitingResumeState && explicitRetry) {
            return new RecoveryTrigger(true, "RESUME_REQUEST", "USER_RETRY_SIGNAL");
        }

        // 检测外部事件回调（如工具执行结果返回）
        if (explicitInterruptEvent) {
            return new RecoveryTrigger(true, "EXTERNAL_EVENT", "EVENT_CALLBACK_SIGNAL");
        }

        // 检测3：延续之前的恢复事件（适用于多轮恢复场景）
        if (contextPackage != null && contextPackage.getRecoveryState() != null) {
            String previousEvent = nullSafe(contextPackage.getRecoveryState().getRecoveryEvent());
            String previousReason = nullSafe(contextPackage.getRecoveryState().getInterruptReason());
            if (!previousEvent.isBlank() && waitingResumeState) {
                return new RecoveryTrigger(true, previousEvent, previousReason);
            }
        }

        // 无恢复需求，返回正常流程标记
        return new RecoveryTrigger(false, "", "");
    }


    private Map<String, Object> buildDecisionStatePayload(OrchestrationDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskState", decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name());
        payload.put("relationalState", decision == null || decision.getRelationalState() == null ? "" : decision.getRelationalState().name());
        return payload;
    }

    private List<ConversationMessage> buildRetrievalConversationContext(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return List.of();
        }
        Object raw = contextPackage.getRuntime().get("recent_messages");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        List<ConversationMessage> messages = rows.stream()
                .map(row -> ConversationMessage.builder()
                        .role(stringValue(row.get("role")))
                        .content(stringValue(row.get("content_text")))
                        .build())
                .filter(item -> item.getRole() != null && !item.getRole().isBlank()
                        && item.getContent() != null && !item.getContent().isBlank())
                .toList();
        if (messages.size() <= 12) {
            return messages;
        }
        return messages.subList(messages.size() - 12, messages.size());
    }

    private List<RetrievalRoute> resolveAllowedRoutes(OrchestrationDecision decision) {
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        if (taskState == TaskRuntimeState.PLANNING
                || taskState == TaskRuntimeState.REPLANNING
                || taskState == TaskRuntimeState.EXECUTING
                || taskState == TaskRuntimeState.REFLECTING) {
            return RetrievalRoute.all();
        }
        return List.of(RetrievalRoute.SEARCH, RetrievalRoute.NATIVE, RetrievalRoute.MODULAR);
    }

    private RetrievalOptions resolveRetrievalOptions(GovernedSignal governedSignal, OrchestrationDecision decision) {
        boolean debug = governedSignal != null && governedSignal.isDebugFlag();
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        long maxLatencyMs = 1200L;
        if (taskState == TaskRuntimeState.PLANNING
                || taskState == TaskRuntimeState.REPLANNING
                || taskState == TaskRuntimeState.EXECUTING
                || taskState == TaskRuntimeState.REFLECTING) {
            maxLatencyMs = 1800L;
        }
        return RetrievalOptions.builder()
                .debug(debug)
                .maxLatencyMs(maxLatencyMs)
                .build();
    }

    private List<Evidence> getEvidences(RetrievalResponse response, RetrievalSource source) {
        if (response == null || response.getEvidences() == null) {
            return Collections.emptyList();
        }
        return response.getEvidences().getOrDefault(source, Collections.emptyList());
    }

    private Map<String, Object> buildBottomRerankTracePayload(RetrievalResponse response,
                                                              List<Map<String, Object>> mcpPreRankedCandidates,
                                                              String ragQuery,
                                                              String memoryQuery,
                                                              String mcpQuery) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queries", Map.of(
                "rag", nullSafe(ragQuery),
                "memory", nullSafe(memoryQuery),
                "mcp", nullSafe(mcpQuery)
        ));
        payload.put("knowledgeBottomRerank", toBottomRerankRows(getEvidences(response, RetrievalSource.KNOWLEDGE), 24));
        payload.put("memoryBottomRerank", toBottomRerankRows(getEvidences(response, RetrievalSource.MEMORY), 24));
        payload.put("preferenceBottomRerank", toBottomRerankRows(getEvidences(response, RetrievalSource.PREFERENCE), 24));
        payload.put("mcpBottomRerank", toMcpBottomRerankRows(mcpPreRankedCandidates, 24));
        return payload;
    }

    private List<Map<String, Object>> toBottomRerankRows(List<Evidence> evidences, int limit) {
        if (evidences == null || evidences.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Evidence evidence : evidences) {
            if (evidence == null) {
                continue;
            }
            if (rows.size() >= Math.max(1, limit)) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            int preRankOrder = rank++;
            row.put("rank", preRankOrder);
            row.put("preRankOrder", preRankOrder);
            row.put("id", nullSafe(evidence.getId()));
            row.put("source", evidence.getSource() == null ? "" : evidence.getSource().name());
            row.put("role", evidence.getRole() == null ? "" : evidence.getRole().name());
            row.put("score", evidence.getScore());
            row.put("preRankScore", evidence.getScore());
            row.put("title", nullSafe(evidence.getTitle()));
            row.put("metadata", evidence.getMetadata() == null ? Map.of() : evidence.getMetadata());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> toMcpBottomRerankRows(List<Map<String, Object>> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (rows.size() >= Math.max(1, limit)) {
                break;
            }
            int preRankOrder = rank++;
            String preRankScore = firstNonBlank(
                    stringValue(candidate.get("score")),
                    firstNonBlank(
                            stringValue(candidate.get("final_score")),
                            stringValue(candidate.get("relevance_score"))
                    )
            );
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", preRankOrder);
            row.put("preRankOrder", preRankOrder);
            row.put("capabilityName", stringValue(candidate.get("capability_name")));
            row.put("capabilityType", stringValue(candidate.get("capability_type")));
            row.put("serverCode", stringValue(candidate.get("server_code")));
            row.put("score", preRankScore);
            row.put("preRankScore", preRankScore);
            row.put("requiresApproval", candidate.get("requires_approval"));
            row.put("sensitivity", stringValue(candidate.get("sensitivity")));
            rows.add(row);
        }
        return rows;
    }

    private List<String> toMemorySnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.MEMORY).stream()
                .map(evidence -> "memory: " + nullSafe(evidence == null ? null : evidence.getContent()))
                .toList();
    }

    private List<String> toPreferenceSnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.PREFERENCE).stream()
                .map(evidence -> "preference: " + nullSafe(evidence == null ? null : evidence.getContent()))
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

    private ContextState buildContextStateFromSummary(ContextState previous,
                                                      SummaryResult summaryResult,
                                                      StructuredContextPackage contextPackage,
                                                      List<EvidenceBlock> activeEvidenceBlocks,
                                                      List<String> activeMcpResourceHints,
                                                      ToolSemanticResult latestToolSemanticResult) {
        List<String> derivedKnowledgeRefs = activeEvidenceBlocks == null ? List.of() : activeEvidenceBlocks.stream()
                .map(EvidenceBlock::getBlockId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        List<String> derivedMemoryRefs = contextPackage == null || contextPackage.getRetrievalState() == null
                ? List.of()
                : previousOrEmpty(contextPackage.getRetrievalState().getSelectedEvidenceRefs());
        List<String> derivedToolRefs = deriveToolEvidenceRefsFromContext(contextPackage, latestToolSemanticResult);
        List<String> derivedMcpPromptRefs = parseMcpHintsByPrefix(activeMcpResourceHints, "prompt_hint:");
        List<String> derivedMcpResourceRefs = parseMcpHintsByPrefix(activeMcpResourceHints, "resource_hint:");
        List<String> derivedMcpWorkflowRefs = parseMcpHintsByPrefix(activeMcpResourceHints, "workflow_hint:");
        List<String> derivedMcpToolRefs = parseMcpHintsByPrefix(activeMcpResourceHints, "tool_hint:");
        if (derivedMcpPromptRefs.isEmpty() && activeMcpResourceHints != null) {
            derivedMcpPromptRefs = activeMcpResourceHints.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .toList();
        }
        boolean stageChanged = contextPackage != null
                && contextPackage.getTaskStateEntity() != null
                && previous != null
                && previous.getLatestStateSnapshot() != null
                && !nullSafe(contextPackage.getTaskStateEntity().getCurrentStage())
                .equalsIgnoreCase(stringValue(previous.getLatestStateSnapshot().get("currentStage")));
        Map<String, Object> latestStateSnapshot = new LinkedHashMap<>(
                summaryResult == null || summaryResult.getStateSnapshot() == null
                        ? Map.of()
                        : summaryResult.getStateSnapshot()
        );
        ActiveRefGovernanceResult governedKnowledgeRefs = governActiveRefs(
                "knowledge",
                derivedKnowledgeRefs,
                previous == null ? List.of() : previous.getActiveKnowledgeRefs(),
                stageChanged,
                false,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMemoryRefs = governActiveRefs(
                "memory",
                derivedMemoryRefs,
                previous == null ? List.of() : previous.getActiveMemoryRefs(),
                stageChanged,
                false,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedToolRefs = governActiveRefs(
                "tool",
                derivedToolRefs,
                previous == null ? List.of() : previous.getActiveToolEvidenceRefs(),
                stageChanged,
                false,
                ACTIVE_TOOL_REF_MAX,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpPromptRefs = governActiveRefs(
                "mcp_prompt",
                derivedMcpPromptRefs,
                previous == null ? List.of() : previous.getActiveMcpPromptRefs(),
                stageChanged,
                false,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpResourceRefs = governActiveRefs(
                "mcp_resource",
                derivedMcpResourceRefs,
                previous == null ? List.of() : previous.getActiveMcpResourceRefs(),
                stageChanged,
                false,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpWorkflowRefs = governActiveRefs(
                "mcp_workflow",
                derivedMcpWorkflowRefs,
                previous == null ? List.of() : previous.getActiveMcpWorkflowRefs(),
                stageChanged,
                false,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        ActiveRefGovernanceResult governedMcpToolRefs = governActiveRefs(
                "mcp_tool",
                derivedMcpToolRefs,
                previous == null ? List.of() : resolveLegacyMcpToolRefs(previous),
                stageChanged,
                false,
                ACTIVE_REF_MAX_PER_CHANNEL,
                latestStateSnapshot
        );
        latestStateSnapshot.put("activeMcpPromptRefs", governedMcpPromptRefs.refs());
        latestStateSnapshot.put("activeMcpResourceRefs", governedMcpResourceRefs.refs());
        latestStateSnapshot.put("activeMcpWorkflowRefs", governedMcpWorkflowRefs.refs());
        latestStateSnapshot.put("activeMcpToolRefs", governedMcpToolRefs.refs());
        latestStateSnapshot.put("activeMcpResourceRefsLegacy", mergeDistinct(governedMcpResourceRefs.refs(), governedMcpToolRefs.refs()));
        return ContextState.builder()
                .latestNarrativeSummary(summaryResult == null ? "" : nullSafe(summaryResult.getNarrativeSummary()))
                .latestStateSnapshot(latestStateSnapshot)
                .activeKnowledgeRefs(governedKnowledgeRefs.refs())
                .activeMemoryRefs(governedMemoryRefs.refs())
                .activeToolEvidenceRefs(governedToolRefs.refs())
                .activeMcpPromptRefs(governedMcpPromptRefs.refs())
                .activeMcpResourceRefs(governedMcpResourceRefs.refs())
                .activeMcpWorkflowRefs(governedMcpWorkflowRefs.refs())
                .activeMcpToolRefs(governedMcpToolRefs.refs())
                .latestContextSnapshotId(previous == null ? "" : nullSafe(previous.getLatestContextSnapshotId()))
                .build();
    }

    private List<String> deriveToolEvidenceRefsFromContext(StructuredContextPackage contextPackage,
                                                           ToolSemanticResult latestToolSemanticResult) {
        if (contextPackage == null || contextPackage.getRuntime() == null) {
            return List.of();
        }
        List<Map<String, Object>> toolRows = safeMapList(contextPackage.getRuntime().get("active_tool_results"));
        List<String> refs = extractToolHistoryRefs(toolRows);
        if (!refs.isEmpty()) {
            return refs;
        }
        if (latestToolSemanticResult != null && latestToolSemanticResult.getToolStatus() != null
                && !latestToolSemanticResult.getToolStatus().isBlank()) {
            return List.of("tool_semantic:" + latestToolSemanticResult.getToolStatus().toLowerCase(Locale.ROOT));
        }
        return List.of();
    }

    private List<String> previousOrEmpty(List<String> refs) {
        return refs == null ? List.of() : refs;
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
            expected.add(status.toLowerCase(Locale.ROOT));
        }
        return toolRows.stream()
                .filter(row -> expected.contains(stringValue(row.get("call_status")).toLowerCase(Locale.ROOT)))
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
            return Integer.parseInt(String.valueOf(value).trim());
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

    private List<String> nonBlankList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value);
    }

    private String resolveLatestToolRawResultRef(String explicitLatestRef,
                                                 List<Map<String, Object>> toolRows,
                                                 ToolState previousToolState) {
        if (explicitLatestRef != null && !explicitLatestRef.isBlank()) {
            return explicitLatestRef;
        }
        if (toolRows != null && !toolRows.isEmpty()) {
            String traceId = stringValue(toolRows.get(0).get("trace_id"));
            if (!traceId.isBlank()) {
                return "tool_execution_trace:id=" + traceId;
            }
            String toolName = stringValue(toolRows.get(0).get("tool_name"));
            String status = normalizeCallStatus(toolRows.get(0).get("call_status"));
            if (!toolName.isBlank()) {
                return "tool_execution_trace:" + toolName + ":" + status;
            }
        }
        if (previousToolState != null && previousToolState.getLastToolRawResultRef() != null && !previousToolState.getLastToolRawResultRef().isBlank()) {
            return previousToolState.getLastToolRawResultRef();
        }
        return "tool_execution_trace:latest";
    }

    private String resolveLatestToolRawResultJson(Map<String, Object> rawToolResultChannel,
                                                  String latestToolRawRef,
                                                  List<Map<String, Object>> toolRows,
                                                  ToolState previousToolState) {
        String fromChannel = extractRawResultFromChannelByRef(rawToolResultChannel, latestToolRawRef);
        if (!fromChannel.isBlank()) {
            return fromChannel;
        }
        String fromRuntimeRows = ToolRawRefResolver.resolveRawJson(latestToolRawRef, toolRows, objectMapper);
        if (!fromRuntimeRows.isBlank()) {
            return fromRuntimeRows;
        }
        if (previousToolState != null && previousToolState.getLastToolRawResultRef() != null
                && !previousToolState.getLastToolRawResultRef().isBlank()) {
            String fromPreviousRef = ToolRawRefResolver.resolveRawJson(
                    previousToolState.getLastToolRawResultRef(),
                    toolRows,
                    objectMapper
            );
            if (!fromPreviousRef.isBlank()) {
                return fromPreviousRef;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractRawResultFromChannelByRef(Map<String, Object> rawToolResultChannel, String rawRef) {
        if (rawToolResultChannel == null || rawToolResultChannel.isEmpty()) {
            return "";
        }
        Object tracesObj = rawToolResultChannel.get("rawToolExecutionTraces");
        if (!(tracesObj instanceof List<?> traces) || traces.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object trace : traces) {
            if (!(trace instanceof Map<?, ?> traceMap)) {
                continue;
            }
            Object normalizedOutput = traceMap.get("normalized_output");
            if (normalizedOutput == null) {
                normalizedOutput = traceMap.get("normalizedOutput");
            }
            if (normalizedOutput == null) {
                normalizedOutput = traceMap.get("raw_output");
            }
            if (normalizedOutput == null) {
                normalizedOutput = traceMap.get("rawOutput");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trace_id", traceMap.get("trace_id") == null ? traceMap.get("traceId") : traceMap.get("trace_id"));
            row.put("tool_name", traceMap.get("tool_name") == null ? traceMap.get("toolName") : traceMap.get("tool_name"));
            row.put("call_status", traceMap.get("call_status") == null ? traceMap.get("callStatus") : traceMap.get("call_status"));
            row.put("normalized_output", normalizedOutput);
            rows.add(row);
        }
        return ToolRawRefResolver.resolveRawJson(rawRef, rows, objectMapper);
    }

    private String truncate(String text, int maxLen) {
        String normalized = text == null ? "" : text;
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen));
    }

    private String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ignore) {
            return "";
        }
    }

    private List<String> resolveActiveToolEvidenceRefs(List<String> explicitHistoryRefs,
                                                       List<Map<String, Object>> toolRows) {
        List<String> refs = new ArrayList<>();
        if (explicitHistoryRefs != null && !explicitHistoryRefs.isEmpty()) {
            refs.addAll(explicitHistoryRefs);
        }
        if (refs.isEmpty() && toolRows != null) {
            refs.addAll(extractToolHistoryRefs(toolRows));
        }
        return refs.stream().filter(ref -> ref != null && !ref.isBlank()).distinct().toList();
    }

    private ActiveRefGovernanceResult governActiveRefs(String channel,
                                                       List<String> currentRefs,
                                                       List<String> previousRefs,
                                                       boolean stageChanged,
                                                       boolean finishedStepsAdvanced,
                                                       int maxSize,
                                                       Map<String, Object> latestStateSnapshot) {
        List<String> current = normalizeRefs(currentRefs);
        List<String> previous = normalizeRefs(previousRefs);
        Map<String, Integer> previousAgeMap = readChannelAgeMap(latestStateSnapshot, channel);
        LinkedHashSet<String> candidateSet = new LinkedHashSet<>();
        candidateSet.addAll(current);
        candidateSet.addAll(previous);
        int ttl = resolveTtlByChannel(channel);
        int extraDecay = 0;
        if (stageChanged) {
            extraDecay += ROUND_DECAY_ON_STAGE_CHANGE;
        }
        if (finishedStepsAdvanced) {
            extraDecay += ROUND_DECAY_ON_STEP_ADVANCED;
        }
        List<ScoredRef> scored = new ArrayList<>();
        Map<String, Integer> nextAgeMap = new LinkedHashMap<>();
        for (String ref : candidateSet) {
            boolean seenThisRound = current.contains(ref);
            int previousAge = previousAgeMap.getOrDefault(ref, 0);
            int nextAge = seenThisRound ? 0 : previousAge + 1 + extraDecay;
            double score = computeRefScore(channel, seenThisRound, nextAge, ttl, stageChanged, finishedStepsAdvanced);
            boolean expired = nextAge > ttl || score <= 0.0;
            if (expired) {
                continue;
            }
            scored.add(new ScoredRef(ref, score, seenThisRound));
            nextAgeMap.put(ref, nextAge);
        }
        scored.sort((a, b) -> {
            if (a.seenThisRound() != b.seenThisRound()) {
                return a.seenThisRound() ? -1 : 1;
            }
            int scoreCompare = Double.compare(b.score(), a.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return a.ref().compareTo(b.ref());
        });
        List<String> governedRefs = scored.stream()
                .map(ScoredRef::ref)
                .limit(maxSize)
                .toList();
        Map<String, Integer> trimmedAgeMap = new LinkedHashMap<>();
        for (String ref : governedRefs) {
            trimmedAgeMap.put(ref, nextAgeMap.getOrDefault(ref, 0));
        }
        writeChannelAgeMap(latestStateSnapshot, channel, trimmedAgeMap);
        return new ActiveRefGovernanceResult(governedRefs, trimmedAgeMap);
    }

    private List<String> normalizeRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        return refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
    }

    private int resolveTtlByChannel(String channel) {
        if ("tool".equals(channel)) {
            return TOOL_REF_TTL;
        }
        if ("mcp_prompt".equals(channel)) {
            return MCP_PROMPT_REF_TTL;
        }
        if ("mcp_resource".equals(channel)) {
            return MCP_RESOURCE_REF_TTL;
        }
        if ("mcp_workflow".equals(channel)) {
            return MCP_WORKFLOW_REF_TTL;
        }
        if ("mcp_tool".equals(channel)) {
            return MCP_TOOL_REF_TTL;
        }
        if ("memory".equals(channel)) {
            return MEMORY_REF_TTL;
        }
        return KNOWLEDGE_REF_TTL;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> readChannelAgeMap(Map<String, Object> latestStateSnapshot, String channel) {
        if (latestStateSnapshot == null || latestStateSnapshot.isEmpty() || channel == null || channel.isBlank()) {
            return Map.of();
        }
        Object metaObj = latestStateSnapshot.get(REF_GOVERNANCE_META_KEY);
        if (!(metaObj instanceof Map<?, ?> metaMap)) {
            return Map.of();
        }
        Object channelObj = metaMap.get(channel);
        if (!(channelObj instanceof Map<?, ?> channelMap)) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : channelMap.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String ref = String.valueOf(entry.getKey()).trim();
            if (ref.isBlank()) {
                continue;
            }
            out.put(ref, intValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void writeChannelAgeMap(Map<String, Object> latestStateSnapshot, String channel, Map<String, Integer> ageMap) {
        if (latestStateSnapshot == null || channel == null || channel.isBlank()) {
            return;
        }
        Map<String, Object> meta;
        Object metaObj = latestStateSnapshot.get(REF_GOVERNANCE_META_KEY);
        if (metaObj instanceof Map<?, ?> map) {
            meta = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                meta.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            meta = new LinkedHashMap<>();
        }
        meta.put(channel, ageMap == null ? Map.of() : ageMap);
        latestStateSnapshot.put(REF_GOVERNANCE_META_KEY, meta);
    }

    private double computeRefScore(String channel,
                                   boolean seenThisRound,
                                   int age,
                                   int ttl,
                                   boolean stageChanged,
                                   boolean finishedStepsAdvanced) {
        if (seenThisRound) {
            return 1.0;
        }
        if (ttl <= 0) {
            return 0.0;
        }
        double freshnessScore = 1.0 - (double) age / (double) ttl;
        if (freshnessScore < 0.0) {
            freshnessScore = 0.0;
        }
        if (stageChanged) {
            freshnessScore -= isStageBoundChannel(channel) ? 0.30 : 0.15;
        }
        if (finishedStepsAdvanced) {
            freshnessScore -= isStageBoundChannel(channel) ? 0.20 : 0.10;
        }
        if (freshnessScore < 0.0) {
            return 0.0;
        }
        return freshnessScore;
    }

    private boolean isStageBoundChannel(String channel) {
        return "tool".equals(channel) || "mcp_prompt".equals(channel) || "mcp_resource".equals(channel);
    }

    private int safeSize(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private String resolveLastToolName(List<Map<String, Object>> toolRows, ToolSemanticResult toolSemanticResult) {
        if (toolRows != null && !toolRows.isEmpty()) {
            String name = stringValue(toolRows.get(0).get("tool_name"));
            if (!name.isBlank()) {
                return name;
            }
        }
        return toolSemanticResult == null ? "" : nullSafe(toolSemanticResult.getToolName());
    }

    private List<String> extractToolHistoryRefs(List<Map<String, Object>> toolRows) {
        if (toolRows == null || toolRows.isEmpty()) {
            return List.of();
        }
        return toolRows.stream()
                .map(row -> {
                    String traceId = stringValue(row.get("trace_id"));
                    if (!traceId.isBlank()) {
                        return "tool_execution_trace:id=" + traceId;
                    }
                    String toolName = stringValue(row.get("tool_name"));
                    String status = normalizeCallStatus(row.get("call_status"));
                    if (toolName.isBlank()) {
                        return "";
                    }
                    return "tool_execution_trace:" + toolName + ":" + status;
                })
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeCallStatus(Object status) {
        String value = stringValue(status);
        if (value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
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

    private List<String> parseMcpHintsByPrefix(List<String> hints, String prefix) {
        if (hints == null || hints.isEmpty() || prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return hints.stream()
                .filter(item -> item != null && !item.isBlank())
                .filter(item -> item.startsWith(prefix))
                .distinct()
                .toList();
    }

    @SafeVarargs
    private final List<String> firstNonEmpty(List<String>... candidates) {
        if (candidates == null || candidates.length == 0) {
            return List.of();
        }
        for (List<String> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                return candidate.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .distinct()
                        .toList();
            }
        }
        return List.of();
    }

    private List<String> resolveLegacyMcpToolRefs(ContextState contextState) {
        if (contextState == null) {
            return List.of();
        }
        List<String> refs = contextState.getActiveMcpToolRefs();
        if (refs != null && !refs.isEmpty()) {
            return refs;
        }
        Map<String, Object> snapshot = safeMap(contextState.getLatestStateSnapshot());
        List<String> legacy = toStringList(snapshot.get("activeMcpResourceRefsLegacy"));
        if (legacy != null && !legacy.isEmpty()) {
            return legacy;
        }
        return contextState.getActiveMcpResourceRefs() == null ? List.of() : contextState.getActiveMcpResourceRefs();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeMapList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Resource> resolveExecutionCandidates(ContextRerankResult rerankResult, List<Map<String, Object>> mcpPreRankedCandidates) {
        List<Map<String, Object>> selected = new ArrayList<>();
        if (rerankResult != null && rerankResult.getSelectedToolCandidates() != null) {
            selected.addAll(rerankResult.getSelectedToolCandidates());
        }
        if (rerankResult != null && rerankResult.getSelectedPromptCandidates() != null) {
            selected.addAll(rerankResult.getSelectedPromptCandidates());
        }
        if (rerankResult != null && rerankResult.getSelectedResourceCandidates() != null) {
            selected.addAll(rerankResult.getSelectedResourceCandidates());
        }
        if (rerankResult != null && rerankResult.getSelectedWorkflowCandidates() != null) {
            selected.addAll(rerankResult.getSelectedWorkflowCandidates());
        }
        if (selected.isEmpty() && rerankResult != null && rerankResult.getSelectedPromptResources() != null) {
            selected.addAll(rerankResult.getSelectedPromptResources());
        }
        if (selected.isEmpty() && mcpPreRankedCandidates != null) {
            selected.addAll(mcpPreRankedCandidates);
        }
        return toolRouter.materializeCandidates(selected, 16);
    }

    // ... existing code ...

    /**
     * 构建蓝图草稿，整合输入重构结果、上下文状态、节点工作集等信息，为主规划服务提供结构化输入。
     * <p>
     * 该方法的主要职责包括：
     * 1. 从任务状态实体中提取当前执行状态的快照信息
     * 2. 基于节点工作集结果收集能力候选提示（工具、提示词、资源、工作流）
     * 3. 聚合知识证据块，提供领域知识和历史参考
     * 4. 组装包含规范化意图、显式目标、业务约束等信息的蓝图草稿对象
     * <p>
     * 生成的蓝图草稿会被主规划服务用于生成完整的执行蓝图，是连接意图理解和计划生成的关键桥梁。
     *
     * @param reconstructionResult 输入重构结果，包含规范化的用户意图和任务目标，不能为空
     * @param contextPackage       结构化上下文包，用于提取任务状态快照，可为空
     * @param nodeWorksetResult    节点工作集结果，包含重排后的能力候选和知识证据，可为空
     * @param decision             编排决策，用于确定当前任务阶段，可为空
     * @return BlueprintDraft 蓝图草稿对象，包含：
     *         - normalizedUserIntent: 规范化的用户意图描述
     *         - explicitTaskGoal: 显式的任务目标
     *         - timeScope: 时间范围约束
     *         - missingSlots: 缺失的参数槽位列表
     *         - businessConstraints: 业务约束条件列表
     *         - currentStage: 当前任务阶段
     *         - currentNode: 当前节点ID
     *         - taskStateSnapshot: 任务状态快照，包含taskId、sessionId、objective等完整状态信息
     *         - workflowHints: 工作流提示列表，最多24个去重后的能力候选（TOOL/PROMPT/RESOURCE/WORKFLOW类型）
     *         - evidenceBlocks: 知识证据块列表，最多20个去重后的相关知识条目
     *         - rationaleByNode: 按节点分组的排序理由映射
     *         如果reconstructionResult为空则返回null
     */
    private BlueprintDraft buildBlueprintDraft(InputReconstructionResult reconstructionResult,
                                               StructuredContextPackage contextPackage,
                                               NodeWorksetResult nodeWorksetResult,
                                               OrchestrationDecision decision) {
        if (reconstructionResult == null) {
            return null;
        }

        // 从任务状态实体中提取当前执行状态的快照信息
        Map<String, Object> taskStateSnapshot = new LinkedHashMap<>();
        if (contextPackage != null && contextPackage.getTaskStateEntity() != null) {
            TaskState state = contextPackage.getTaskStateEntity();
            taskStateSnapshot.put("taskId", nullSafe(state.getTaskId()));
            taskStateSnapshot.put("sessionId", nullSafe(state.getSessionId()));
            taskStateSnapshot.put("objective", nullSafe(state.getObjective()));
            taskStateSnapshot.put("currentStage", nullSafe(state.getCurrentStage()));
            taskStateSnapshot.put("currentNode", nullSafe(state.getCurrentNode()));
            taskStateSnapshot.put("confirmedSlots", state.getConfirmedSlots() == null ? Map.of() : state.getConfirmedSlots());
            taskStateSnapshot.put("pendingQuestions", state.getPendingQuestions() == null ? List.of() : state.getPendingQuestions());
            taskStateSnapshot.put("nextActionHint", nullSafe(state.getNextActionHint()));
        }

        // 收集能力候选提示，包括工具、提示词、资源和工作流四种类型
        List<Map<String, Object>> workflowHints = new ArrayList<>();
        if (nodeWorksetResult != null && nodeWorksetResult.getSelectedToolCandidateNames() != null) {
            for (String name : nodeWorksetResult.getSelectedToolCandidateNames()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                workflowHints.add(Map.of(
                        "capabilityName", name,
                        "capabilityType", "TOOL"
                    ));
            }
        }
        if (nodeWorksetResult != null && nodeWorksetResult.getSelectedPromptCandidateNames() != null) {
            for (String name : nodeWorksetResult.getSelectedPromptCandidateNames()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                workflowHints.add(Map.of(
                        "capabilityName", name,
                        "capabilityType", "PROMPT"
                ));
            }
        }
        if (nodeWorksetResult != null && nodeWorksetResult.getSelectedResourceCandidateNames() != null) {
            for (String name : nodeWorksetResult.getSelectedResourceCandidateNames()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                workflowHints.add(Map.of(
                        "capabilityName", name,
                        "capabilityType", "RESOURCE"
                ));
            }
        }
        if (nodeWorksetResult != null && nodeWorksetResult.getSelectedWorkflowCandidateNames() != null) {
            for (String name : nodeWorksetResult.getSelectedWorkflowCandidateNames()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                workflowHints.add(Map.of(
                        "capabilityName", name,
                        "capabilityType", "WORKFLOW"
                ));
            }
        }

        // 如果没有其他类型的提示，使用选中的提示词资源名称作为备选
        if (workflowHints.isEmpty() && nodeWorksetResult != null && nodeWorksetResult.getSelectedPromptResourceNames() != null) {
            for (String name : nodeWorksetResult.getSelectedPromptResourceNames()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                workflowHints.add(Map.of(
                        "capabilityName", name,
                        "capabilityType", "PROMPT"
                ));
            }
        }

        // 聚合知识证据块，将节点工作集中的知识证据转换为蓝图草稿的证据格式
        List<Map<String, Object>> evidenceBlocks = new ArrayList<>();
        if (nodeWorksetResult != null && nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks() != null) {
            for (EvidenceBlock block : nodeWorksetResult.getSelectedKnowledgeEvidenceBlocks()) {
                if (block == null) {
                    continue;
                }
                evidenceBlocks.add(Map.of(
                        "id", nullSafe(block.getBlockId()),
                        "title", nullSafe(block.getTitle()),
                        "content", nullSafe(block.getContent()),
                        "sourceType", nullSafe(block.getSourceType()),
                        "score", block.getScore() == null ? "" : String.valueOf(block.getScore())
                ));
            }
        }

        // 组装并返回蓝图草稿对象，限制workflowHints最多24个、evidenceBlocks最多20个
        return BlueprintDraft.builder()
                .normalizedUserIntent(nullSafe(reconstructionResult.getNormalizedUserIntent()))
                .explicitTaskGoal(nullSafe(reconstructionResult.getExplicitTaskGoal()))
                .timeScope(nullSafe(reconstructionResult.getTimeScope()))
                .missingSlots(reconstructionResult.getMissingSlots() == null ? List.of() : reconstructionResult.getMissingSlots())
                .businessConstraints(reconstructionResult.getBusinessConstraints() == null ? List.of() : reconstructionResult.getBusinessConstraints())
                .currentStage(decision == null || decision.getTaskState() == null ? "UNKNOWN" : decision.getTaskState().name())
                .currentNode(String.valueOf(contextNodeId(contextPackage)))
                .taskStateSnapshot(taskStateSnapshot)
                .workflowHints(workflowHints.stream().distinct().limit(24).toList())
                .evidenceBlocks(evidenceBlocks.stream().distinct().limit(20).toList())
                .rationaleByNode(nodeWorksetResult == null || nodeWorksetResult.getRerankRationaleByNode() == null
                        ? Map.of()
                        : new LinkedHashMap<>(nodeWorksetResult.getRerankRationaleByNode()))
                .build();
    }

    // ... existing code ...


    private Map<String, Object> buildBlueprintEntryOverrides(BlueprintDraft draft) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("entry_type", "BLUEPRINT");
        overrides.put("blueprint_draft_ready", draft != null);
        if (draft != null) {
            overrides.put("blueprint_normalized_user_intent", nullSafe(draft.getNormalizedUserIntent()));
            overrides.put("blueprint_explicit_task_goal", nullSafe(draft.getExplicitTaskGoal()));
            overrides.put("blueprint_time_scope", nullSafe(draft.getTimeScope()));
            overrides.put("blueprint_missing_slots", draft.getMissingSlots() == null ? List.of() : draft.getMissingSlots());
            overrides.put("blueprint_business_constraints", draft.getBusinessConstraints() == null ? List.of() : draft.getBusinessConstraints());
            overrides.put("blueprint_draft_payload", objectMapper.convertValue(draft, Map.class));
            overrides.put("task_state_patch", Map.of(
                    "objective", nullSafe(draft.getExplicitTaskGoal()),
                    "current_stage", nullSafe(draft.getCurrentStage()),
                    "current_node", nullSafe(draft.getCurrentNode()),
                    "pending_questions", draft.getMissingSlots() == null ? List.of() : draft.getMissingSlots()
            ));
            overrides.put("retrieval_state_patch", Map.of(
                    "reconstructed_intent", nullSafe(draft.getNormalizedUserIntent()),
                    "entry_type", "BLUEPRINT",
                    "draft_ready", true
            ));
        }
        return overrides;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignore) {
            return "{}";
        }
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
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
        if (contextPackage.getTaskStateEntity() != null) {
            return toLong(contextPackage.getTaskStateEntity().getTaskId());
        }
        return null;
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return null;
        }
        Object working = contextPackage.getTaskContext().get("working_memory");
        if (working instanceof Map<?, ?> row) {
            Long runtimeNode = toLong(row.get("active_node_id"));
            if (runtimeNode != null) {
                return runtimeNode;
            }
        }
        if (contextPackage.getTaskStateEntity() != null) {
            return toLong(contextPackage.getTaskStateEntity().getCurrentNode());
        }
        return null;
    }

    private Long toLong(Object value) {
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

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private RetrievalResponse filterInvalidatedEvidences(RetrievalResponse response, List<String> invalidatedRefs) {
        if (response == null || invalidatedRefs == null || invalidatedRefs.isEmpty() || response.getEvidences() == null) {
            return response;
        }
        Set<String> blocked = invalidatedRefs.stream()
                .filter(item -> item != null && !item.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (blocked.isEmpty()) {
            return response;
        }
        Map<RetrievalSource, List<Evidence>> filtered = new LinkedHashMap<>();
        for (RetrievalSource source : RetrievalSource.values()) {
            List<Evidence> rows = response.getEvidences().getOrDefault(source, List.of());
            filtered.put(source, rows.stream()
                    .filter(item -> item != null)
                    .filter(item -> !blocked.contains(stringValue(item.getId())))
                    .toList());
        }
        return RetrievalResponse.builder()
                .route(response.getRoute())
                .rewrittenQuery(response.getRewrittenQuery())
                .evidences(filtered)
                .evidenceRoleGroups(response.getEvidenceRoleGroups() == null ? Map.of() : response.getEvidenceRoleGroups())
                .meta(response.getMeta() == null ? Map.of() : response.getMeta())
                .build();
    }

    private List<Map<String, Object>> filterInvalidatedCapabilities(List<Map<String, Object>> rows, List<String> invalidatedCapabilityNames) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if (invalidatedCapabilityNames == null || invalidatedCapabilityNames.isEmpty()) {
            return rows;
        }
        Set<String> blocked = invalidatedCapabilityNames.stream()
                .filter(item -> item != null && !item.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (blocked.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> {
                    String capabilityName = stringValue(row.get("capability_name"));
                    return capabilityName.isBlank() || !blocked.contains(capabilityName);
                })
                .toList();
    }

    private List<String> extractKnowledgeEvidenceRefs(List<EvidenceBlock> selectedKnowledgeEvidenceBlocks) {
        if (selectedKnowledgeEvidenceBlocks == null || selectedKnowledgeEvidenceBlocks.isEmpty()) {
            return List.of();
        }
        return selectedKnowledgeEvidenceBlocks.stream()
                .map(EvidenceBlock::getBlockId)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }

    private List<String> extractCapabilityNames(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(row -> stringValue(row.get("capability_name")))
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, Object> buildStructuredRecoveryPayload(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return Map.of();
        }
        Map<String, Object> runtimePointers = new LinkedHashMap<>();
        runtimePointers.put("snapshotId", contextPackage.getContextState() == null ? "" : nullSafe(contextPackage.getContextState().getLatestContextSnapshotId()));
        runtimePointers.put("planId", contextPlanId(contextPackage));
        runtimePointers.put("nodeId", contextNodeId(contextPackage));
        runtimePointers.put("activeRefs", contextPackage.getContextState() == null ? Map.of() : Map.of(
                "activeKnowledgeRefs", contextPackage.getContextState().getActiveKnowledgeRefs() == null ? List.of() : contextPackage.getContextState().getActiveKnowledgeRefs(),
                "activeMemoryRefs", contextPackage.getContextState().getActiveMemoryRefs() == null ? List.of() : contextPackage.getContextState().getActiveMemoryRefs(),
                "activeToolEvidenceRefs", contextPackage.getContextState().getActiveToolEvidenceRefs() == null ? List.of() : contextPackage.getContextState().getActiveToolEvidenceRefs(),
                "activeMcpPromptRefs", contextPackage.getContextState().getActiveMcpPromptRefs() == null ? List.of() : contextPackage.getContextState().getActiveMcpPromptRefs(),
                "activeMcpResourceRefs", contextPackage.getContextState().getActiveMcpResourceRefs() == null ? List.of() : contextPackage.getContextState().getActiveMcpResourceRefs(),
                "activeMcpWorkflowRefs", contextPackage.getContextState().getActiveMcpWorkflowRefs() == null ? List.of() : contextPackage.getContextState().getActiveMcpWorkflowRefs(),
                "activeMcpToolRefs", contextPackage.getContextState().getActiveMcpToolRefs() == null ? List.of() : contextPackage.getContextState().getActiveMcpToolRefs()
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskState", contextPackage.getTaskStateEntity() == null ? Map.of() : objectMapper.convertValue(contextPackage.getTaskStateEntity(), Map.class));
        payload.put("retrievalState", contextPackage.getRetrievalState() == null ? Map.of() : objectMapper.convertValue(contextPackage.getRetrievalState(), Map.class));
        payload.put("toolState", contextPackage.getToolState() == null ? Map.of() : objectMapper.convertValue(contextPackage.getToolState(), Map.class));
        payload.put("contextState", contextPackage.getContextState() == null ? Map.of() : objectMapper.convertValue(contextPackage.getContextState(), Map.class));
        payload.put("recoveryState", contextPackage.getRecoveryState() == null ? Map.of() : objectMapper.convertValue(contextPackage.getRecoveryState(), Map.class));
        payload.put("runtimePointers", runtimePointers);
        return payload;
    }

    private Map<String, List<String>> buildFinalSnapshotActiveRefs(MainModelExecutionRequest request,
                                                                   StructuredContextPackage contextPackage) {
        ContextRerankResult rerankResult = request == null ? null : request.getRerankResult();
        List<String> knowledgeRefs = extractKnowledgeRefs(rerankResult);
        List<String> memoryRefs = rerankResult == null || rerankResult.getSelectedMemoryHints() == null
                ? List.of()
                : rerankResult.getSelectedMemoryHints().stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        List<String> mcpPromptRefs = rerankResult == null || rerankResult.getSelectedPromptCandidates() == null
                ? List.of()
                : rerankResult.getSelectedPromptCandidates().stream()
                .map(this::toJsonSafe)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        List<String> mcpResourceRefs = rerankResult == null || rerankResult.getSelectedResourceCandidates() == null
                ? List.of()
                : rerankResult.getSelectedResourceCandidates().stream()
                .map(this::toJsonSafe)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        List<String> mcpWorkflowRefs = rerankResult == null || rerankResult.getSelectedWorkflowCandidates() == null
                ? List.of()
                : rerankResult.getSelectedWorkflowCandidates().stream()
                .map(this::toJsonSafe)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        List<String> mcpToolRefs = rerankResult == null || rerankResult.getSelectedToolCandidates() == null
                ? List.of()
                : rerankResult.getSelectedToolCandidates().stream()
                .map(this::toJsonSafe)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        List<String> toolRefs = extractToolRefsFromRawChannel(request == null ? null : request.getRawToolResultChannel());
        if ((toolRefs == null || toolRefs.isEmpty()) && contextPackage != null && contextPackage.getContextState() != null) {
            toolRefs = contextPackage.getContextState().getActiveToolEvidenceRefs();
        }
        Map<String, List<String>> activeRefs = new LinkedHashMap<>();
        activeRefs.put("activeKnowledgeRefs", knowledgeRefs == null ? List.of() : knowledgeRefs);
        activeRefs.put("activeMemoryRefs", memoryRefs == null ? List.of() : memoryRefs);
        activeRefs.put("activeToolEvidenceRefs", toolRefs == null ? List.of() : toolRefs);
        activeRefs.put("activeMcpPromptRefs", mcpPromptRefs == null ? List.of() : mcpPromptRefs);
        activeRefs.put("activeMcpResourceRefs", mcpResourceRefs == null ? List.of() : mcpResourceRefs);
        activeRefs.put("activeMcpWorkflowRefs", mcpWorkflowRefs == null ? List.of() : mcpWorkflowRefs);
        activeRefs.put("activeMcpToolRefs", mcpToolRefs == null ? List.of() : mcpToolRefs);
        activeRefs.put("activeMcpResourceRefsLegacy", mergeDistinct(
                mcpResourceRefs == null ? List.of() : mcpResourceRefs,
                mcpToolRefs == null ? List.of() : mcpToolRefs
        ));
        return activeRefs;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractToolRefsFromRawChannel(Map<String, Object> rawToolResultChannel) {
        if (rawToolResultChannel == null || rawToolResultChannel.isEmpty()) {
            return List.of();
        }
        Object refsObj = rawToolResultChannel.get("toolHistoryRefs");
        if (!(refsObj instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
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
        return ContextNodeTemplatePolicy.forTaskNode(taskState, currentNode, "");
    }

    private List<String> extractTaskKnowledgeSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return List.of();
        }
        Object raw = contextPackage.getTaskContext().get("knowledge");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "title: " + nullSafe(stringValue(item.get("title"))) + "\ncontent: " + nullSafe(stringValue(item.get("chunk_text"))))
                .toList();
    }

    private List<String> extractTaskLongTermSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return List.of();
        }
        List<String> snippets = new ArrayList<>();
        Object factsRaw = contextPackage.getTaskContext().get("task_facts");
        if (factsRaw instanceof List<?> facts) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) facts;
            snippets.addAll(rows.stream()
                    .map(item -> "task_fact: " + nullSafe(stringValue(item.get("fact_key"))) + "=" + nullSafe(stringValue(item.get("fact_value_text"))))
                    .toList());
        }
        Object episodesRaw = contextPackage.getTaskContext().get("task_episodes");
        if (episodesRaw instanceof List<?> episodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) episodes;
            snippets.addAll(rows.stream()
                    .map(item -> "task_episode: " + nullSafe(stringValue(item.get("episode_type"))) + " | " + nullSafe(stringValue(item.get("trajectory_summary"))))
                    .toList());
        }
        return snippets;
    }

    private List<String> extractWorkingMemorySnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getTaskContext() == null) {
            return List.of();
        }
        Object raw = contextPackage.getTaskContext().get("working_memory");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("working.goal_raw: " + nullSafe(stringValue(map.get("goal_raw"))));
        out.add("working.goal_refined: " + nullSafe(stringValue(map.get("goal_refined"))));
        out.add("working.unresolved_questions: " + nullSafe(stringValue(map.get("unresolved_questions_json"))));
        out.add("working.risks: " + nullSafe(stringValue(map.get("risks_json"))));
        return out;
    }

    private List<String> extractRelationalPreferenceSnippets(StructuredContextPackage contextPackage) {
        if (contextPackage == null || contextPackage.getRelationalContext() == null) {
            return List.of();
        }
        Object raw = contextPackage.getRelationalContext().get("semantic_facts");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "relation_pref: " + nullSafe(stringValue(item.get("fact_key"))) + "=" + nullSafe(stringValue(item.get("fact_value_text"))))
                .toList();
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
                .map(item -> nullSafe(stringValue(item.get("role"))) + ": " + nullSafe(stringValue(item.get("content_text"))))
                .toList();
    }

    private List<String> buildNodeScopedMemorySnippets(ContextNodeTemplatePolicy policy,
                                                       List<String> workingMemorySnippets,
                                                       List<String> runtimeMemorySnippets,
                                                       List<String> retrievedMemorySnippets,
                                                       List<String> longTermMemorySnippets) {
        ContextNodeTemplatePolicy effective = policy == null ? ContextNodeTemplatePolicy.defaultPolicy() : policy;
        List<String> out = new ArrayList<>();
        if (effective.isIncludeWorkingMemory()) {
            out.addAll(limitSnippets(workingMemorySnippets, effective.getMaxWorkingMemoryItems()));
        }
        if (effective.isIncludeRuntimeMemory()) {
            out.addAll(limitSnippets(runtimeMemorySnippets, effective.getMaxRuntimeMemoryItems()));
        }
        if (effective.isIncludeRetrievedMemory()) {
            out.addAll(limitSnippets(retrievedMemorySnippets, effective.getMaxRetrievedMemoryItems()));
        }
        if (effective.isIncludeLongTermMemory()) {
            out.addAll(limitSnippets(longTermMemorySnippets, effective.getMaxLongTermMemoryItems()));
        }
        return out.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    private List<String> limitSnippets(List<String> snippets, int maxItems) {
        if (snippets == null || snippets.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        return snippets.stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(maxItems)
                .toList();
    }

    private ToolTraceRefs persistToolExecutionTraces(String sessionId,
                                                     Long planId,
                                                     Long nodeId,
                                                     String userInput,
                                                     String toolContext,
                                                     String chainStatus,
                                                     String chainError,
                                                     long chainLatencyMs,
                                                     List<Map<String, Object>> traces) {
        List<Map<String, Object>> safeTraces = traces == null ? List.of() : traces;
        List<String> historyRefs = new ArrayList<>();
        String latestRawRef = "";
        if (safeTraces.isEmpty()) {
            Long traceId = runtimeAuditService.persistToolExecutionTraceAndReturnId(
                    sessionId,
                    planId,
                    nodeId,
                    "agent_tool_chain",
                    chainStatus,
                    toJsonSafe(Map.of("userInput", userInput == null ? "" : userInput)),
                    toolContext,
                    chainError,
                    Math.max(0L, chainLatencyMs)
            );
            latestRawRef = toTraceRef(traceId, "agent_tool_chain", chainStatus);
            historyRefs.add(latestRawRef);
            return new ToolTraceRefs(latestRawRef, historyRefs.stream().filter(ref -> ref != null && !ref.isBlank()).distinct().toList());
        }
        int sequence = 1;
        for (Map<String, Object> trace : safeTraces) {
            String normalizedToolName = normalizeToolName(trace == null ? null : trace.get("tool_name"), sequence);
            String normalizedStatus = normalizeCallStatus(trace == null ? null : trace.get("call_status"));
            Map<String, Object> normalizedInput = new LinkedHashMap<>();
            normalizedInput.put("sequence", sequence);
            normalizedInput.put("source_type", trace == null ? "" : stringValue(trace.get("source_type")));
            normalizedInput.put("payload", trace == null ? Map.of() : trace.getOrDefault("normalized_input", Map.of()));
            Map<String, Object> normalizedOutput = new LinkedHashMap<>();
            normalizedOutput.put("sequence", sequence);
            normalizedOutput.put("source_type", trace == null ? "" : stringValue(trace.get("source_type")));
            normalizedOutput.put("payload", trace == null ? Map.of() : trace.getOrDefault("normalized_output", Map.of()));
            Long traceId = runtimeAuditService.persistToolExecutionTraceAndReturnId(
                    sessionId,
                    planId,
                    nodeId,
                    normalizedToolName,
                    normalizedStatus,
                    toJsonSafe(normalizedInput),
                    toJsonSafe(normalizedOutput),
                    trace == null ? "" : stringValue(trace.get("error_message")),
                    normalizeLatency(trace == null ? null : trace.get("latency_ms"))
            );
            String traceRef = toTraceRef(traceId, normalizedToolName, normalizedStatus);
            historyRefs.add(traceRef);
            if (latestRawRef.isBlank()) {
                latestRawRef = traceRef;
            }
            sequence++;
        }
        Long chainTraceId = runtimeAuditService.persistToolExecutionTraceAndReturnId(
                sessionId,
                planId,
                nodeId,
                "agent_tool_chain",
                chainStatus,
                toJsonSafe(Map.of(
                        "userInput", userInput == null ? "" : userInput,
                        "traceCount", safeTraces.size()
                )),
                toJsonSafe(Map.of(
                        "toolContext", toolContext == null ? "" : toolContext,
                        "chainStatus", chainStatus == null ? "" : chainStatus
                )),
                chainError,
                Math.max(0L, chainLatencyMs)
        );
        historyRefs.add(toTraceRef(chainTraceId, "agent_tool_chain", chainStatus));
        if (latestRawRef.isBlank()) {
            latestRawRef = toTraceRef(chainTraceId, "agent_tool_chain", chainStatus);
        }
        return new ToolTraceRefs(latestRawRef, historyRefs.stream().filter(ref -> ref != null && !ref.isBlank()).distinct().toList());
    }

    private String normalizeToolName(Object rawName, int sequence) {
        String name = stringValue(rawName);
        if (name == null || name.isBlank()) {
            return "tool_call_" + sequence;
        }
        return name;
    }

    private Long normalizeLatency(Object rawLatency) {
        Long value = toLong(rawLatency);
        if (value == null) {
            return null;
        }
        return Math.max(0L, value);
    }

    private String toTraceRef(Long traceId, String toolName, String callStatus) {
        if (traceId != null && traceId > 0L) {
            return "tool_execution_trace:id=" + traceId;
        }
        String normalizedTool = toolName == null || toolName.isBlank() ? "agent_tool_chain" : toolName;
        String normalizedStatus = callStatus == null || callStatus.isBlank() ? "UNKNOWN" : callStatus.toUpperCase(Locale.ROOT);
        return "tool_execution_trace:" + normalizedTool + ":" + normalizedStatus;
    }

    private List<Map<String, Object>> toExecutionCandidateMaps(List<Resource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Resource resource : resources) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", resource.getName());
            row.put("type", resource.getType() == null ? "" : resource.getType().name());
            row.put("serverCode", resource.getServerCode());
            row.put("resourceUri", resource.getResourceUri());
            row.put("requiresApproval", resource.getRequiresApproval());
            row.put("sensitivity", resource.getSensitivity() == null ? "" : resource.getSensitivity().name());
            out.add(row);
        }
        return out;
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

    private ToolSemanticResult resolveToolSemanticFromRequest(RoundToolSemanticRequest request) {
        /**
         * 从轮次请求中统一提取工具语义输入，并完成语义翻译、校验归一化和审计记录。
         */
        if (request == null) {
            return fallbackToolSemanticResult("agent_tool_chain", "", "", "round_tool_semantic_request_missing");
        }
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
            translated = toolSemanticAgent.translate(
                    toolName,
                    toolDescription,
                    nullSafe(request.getToolContext()),
                    taskState,
                    explicitGoal
            );
        } catch (Exception ex) {
            translated = fallbackToolSemanticResult(toolName, toolDescription, request.getToolContext(), ex.getMessage());
        }
        boolean translationFailed = translated == null
                || Boolean.TRUE.equals(safeMap(translated.getSemanticPayload()).get("semantic_translation_failed"));
        ToolSemanticResult safeTranslated = translated == null
                ? fallbackToolSemanticResult(toolName, toolDescription, request.getToolContext(), "tool_semantic_translation_empty")
                : translated;
        ToolSemanticResultValidator.ValidationResult validation = toolSemanticResultValidator.validate(safeTranslated, contextPackage);
        if (validation.normalized() != null) {
            safeTranslated = validation.normalized();
        }
        if (translationFailed) {
            safeTranslated = fallbackToolSemanticResult(
                    firstNonBlank(safeTranslated.getToolName(), toolName),
                    firstNonBlank(safeTranslated.getToolDescription(), toolDescription),
                    request.getToolContext(),
                    firstNonBlank(
                            stringValue(safeMap(safeTranslated.getSemanticPayload()).get("failure_reason")),
                            "tool_semantic_translation_failed"
                    )
            );
        }
        runtimeAuditService.persistDecisionRecord(
                request.getSessionId(),
                planId,
                nodeId,
                "TOOL_SEMANTIC_VALIDATION",
                validation.valid() ? firstNonBlank(stage, "ROUND") + " semantic validation passed"
                        : firstNonBlank(stage, "ROUND") + " semantic validation failed",
                validation.valid() ? "{}" : toJsonSafe(Map.of(
                        "issues", validation.issues() == null ? List.of() : validation.issues(),
                        "stage", stage
                ))
        );
        if (!validation.valid() && validation.issues() != null && validation.issues().contains("schema_invalid")) {
            runtimeAuditService.persistDecisionRecord(
                    request.getSessionId(),
                    planId,
                    nodeId,
                    "TOOL_SEMANTIC_SCHEMA_INVALID",
                    "semantic result rejected by schema, normalized fallback applied",
                    toJsonSafe(Map.of(
                            "issues", validation.issues() == null ? List.of() : validation.issues(),
                            "stage", stage
                    ))
            );
        }
        String rawResultRef = resolveLatestRawResultRef(request.getRawToolResultChannel(), null);
        String semanticTraceId = buildTraceId("TOOL_SEMANTIC", request.getSessionId(), planId, nodeId);
        runtimeAuditService.persistDecisionRecord(
                request.getSessionId(),
                planId,
                nodeId,
                "TOOL_SEMANTIC_PIPELINE_TRACE",
                firstNonBlank(stage, "ROUND") + " semantic pipeline traced",
                toJsonSafe(Map.of(
                        "traceId", semanticTraceId,
                        "rawResultRef", rawResultRef,
                        "rawDigest", nullSafe(safeTranslated.getRawResultDigest()),
                        "semanticResult", safeTranslated,
                        "validationIssues", validation.issues() == null ? List.of() : validation.issues()
                ))
        );
        runtimeAuditService.persistDecisionRecord(
                request.getSessionId(),
                planId,
                nodeId,
                "TOOL_SEMANTIC_TRANSLATION",
                firstNonBlank(stage, "ROUND") + " tool semantic translated",
                toJsonSafe(safeTranslated)
        );
        toolSemanticTraceLogger.log(request.getSessionId(), planId, nodeId, safeTranslated);
        return safeTranslated;
    }

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

    @SuppressWarnings("unchecked")
    private void persistPromptSnapshotRefs(String sessionId,
                                           Long roundId,
                                           Long nodeId,
                                           String snapshotId,
                                           AssembledContext assembledContext) {
        if (promptSnapshotBridgeService == null || assembledContext == null || assembledContext.getPromptAssemblyMeta() == null) {
            return;
        }
        try {
            Map<String, Object> meta = assembledContext.getPromptAssemblyMeta();
            String policyId = stringValue(meta.get("policyId"));
            String assemblerVersion = stringValue(meta.get("assemblerVersion"));
            Object refsRaw = meta.get("promptRefs");
            if (!(refsRaw instanceof List<?> refs) || refs.isEmpty()) {
                refsRaw = meta.get("allPromptRefs");
            }
            if (!(refsRaw instanceof List<?> refs) || refs.isEmpty()) {
                return;
            }
            List<ResolvedPromptItem> items = new ArrayList<>();
            for (Object ref : refs) {
                if (!(ref instanceof Map<?, ?> row)) {
                    continue;
                }
                items.add(ResolvedPromptItem.builder()
                        .itemId(readPromptRefLong(row, "promptItemId", "itemId"))
                        .versionId(readPromptRefLong(row, "promptItemVersionId", "versionId"))
                        .key(readPromptRefString(row, "promptKey", "key"))
                        .value(stringValue(row.get("value")))
                        .version(readPromptRefString(row, "promptVersion", "version"))
                        .runtimeSlot(stringValue(row.get("runtimeSlot")))
                        .matchReason(stringValue(row.get("matchReason")))
                        .category(stringValue(row.get("category")))
                        .assemblerVersion(firstNonBlank(stringValue(row.get("assemblerVersion")), assemblerVersion))
                        .build());
            }
            if (items.isEmpty()) {
                return;
            }
            Object slotMappingRaw = meta.get("slotMapping");
            if (!(slotMappingRaw instanceof Map<?, ?> mapping) || mapping.isEmpty()) {
                slotMappingRaw = meta.get("allSlotMapping");
            }
            Map<String, List<ResolvedPromptItem>> slotMapping = parseSnapshotSlotMapping(
                    slotMappingRaw,
                    assemblerVersion
            );
            PromptResolveResult resolveResult = PromptResolveResult.builder()
                    .policyId(policyId)
                    .matchedItems(items)
                    .slotMapping(slotMapping)
                    .build();
            Map<String, Object> snapshotPayload = promptSnapshotBridgeService.buildSnapshotPayload(resolveResult, policyId);
            promptSnapshotBridgeService.persistSnapshotRefs(sessionId, roundId, nodeId, snapshotId, snapshotPayload);
        } catch (Exception ignore) {
        // 此处异常不能影响主模型主链路，失败时仅记录并继续后续流程。
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<ResolvedPromptItem>> parseSnapshotSlotMapping(Object slotMappingRaw, String defaultAssemblerVersion) {
        if (!(slotMappingRaw instanceof Map<?, ?> rawMapping) || rawMapping.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ResolvedPromptItem>> slotMapping = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMapping.entrySet()) {
            String slot = stringValue(entry.getKey());
            if (slot.isBlank()) {
                continue;
            }
            if (!(entry.getValue() instanceof List<?> itemsRaw) || itemsRaw.isEmpty()) {
                slotMapping.put(slot, List.of());
                continue;
            }
            List<ResolvedPromptItem> items = new ArrayList<>();
            for (Object itemRaw : itemsRaw) {
                if (!(itemRaw instanceof Map<?, ?> row)) {
                    continue;
                }
                items.add(ResolvedPromptItem.builder()
                        .itemId(readPromptRefLong(row, "promptItemId", "itemId"))
                        .versionId(readPromptRefLong(row, "promptItemVersionId", "versionId"))
                        .key(readPromptRefString(row, "promptKey", "key"))
                        .value(stringValue(row.get("value")))
                        .version(readPromptRefString(row, "promptVersion", "version"))
                        .runtimeSlot(stringValue(row.get("runtimeSlot")))
                        .matchReason(stringValue(row.get("matchReason")))
                        .category(stringValue(row.get("category")))
                        .assemblerVersion(firstNonBlank(stringValue(row.get("assemblerVersion")), defaultAssemblerVersion))
                        .build());
            }
            slotMapping.put(slot, items);
        }
        return slotMapping;
    }

    private Long readPromptRefLong(Map<?, ?> row, String primaryKey, String fallbackKey) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Long primary = toLong(row.get(primaryKey));
        return primary != null ? primary : toLong(row.get(fallbackKey));
    }

    private String readPromptRefString(Map<?, ?> row, String primaryKey, String fallbackKey) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        return firstNonBlank(stringValue(row.get(primaryKey)), stringValue(row.get(fallbackKey)));
    }

    @SuppressWarnings("unchecked")
    private Long resolveRoundId(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return null;
        }
        try {
            Map<String, Object> runtime = contextPackage.getRuntime();
            if (runtime != null && !runtime.isEmpty()) {
                Object session = runtime.get("session");
                if (session instanceof Map<?, ?> row) {
                    Long roundId = toLong(row.get("current_round_id"));
                    if (roundId != null) {
                        return roundId;
                    }
                    roundId = toLong(row.get("round_id"));
                    if (roundId != null) {
                        return roundId;
                    }
                }
                Long direct = toLong(runtime.get("current_round_id"));
                if (direct != null) {
                    return direct;
                }
                direct = toLong(runtime.get("round_id"));
                if (direct != null) {
                    return direct;
                }
            }
            Map<String, Object> taskContext = contextPackage.getTaskContext();
            if (taskContext != null && !taskContext.isEmpty()) {
                Long roundId = toLong(taskContext.get("round_id"));
                if (roundId != null) {
                    return roundId;
                }
                Object working = taskContext.get("working_memory");
                if (working instanceof Map<?, ?> row) {
                    roundId = toLong(row.get("round_id"));
                    if (roundId != null) {
                        return roundId;
                    }
                }
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
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
                + ", server=" + firstNonBlank(first.getServerCode(), "local")
                + ", name=" + firstNonBlank(first.getName(), "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> safeStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            out.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item))
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 工具追踪引用集合，保存最近一次引用和历史引用列表。
     */
    private record ToolTraceRefs(String latestRawRef, List<String> historyRefs) {
        private static ToolTraceRefs empty() {
            return new ToolTraceRefs("", List.of());
        }
    }

    /**
     * 恢复触发结果，描述当前轮次是否需要进入恢复链路及对应事件原因。
     */
    private record RecoveryTrigger(boolean shouldRecover, String recoveryEvent, String interruptReason) {
    }

    /**
     * 恢复刷新计划，描述恢复链路中需要立即刷新的上下文子系统。
     */
    private record RecoveryRefreshPlan(boolean refreshRagNow,
                                       boolean refreshMcpNow,
                                       boolean reassembleNow,
                                       List<String> invalidatedEvidenceRefs,
                                       List<String> invalidatedCapabilityNames,
                                       Map<String, String> invalidationReasonsByRef) {
        private static RecoveryRefreshPlan empty() {
            return new RecoveryRefreshPlan(false, false, false, List.of(), List.of(), Map.of());
        }
    }

    /**
     * 引用评分结果，记录单条证据引用的治理分值与当轮是否已出现。
     */
    private record ScoredRef(String ref, double score, boolean seenThisRound) {
    }

    /**
     * 活跃引用治理结果，保存最终保留的引用列表及其年龄信息。
     */
    private record ActiveRefGovernanceResult(List<String> refs, Map<String, Integer> ageByRef) {
    }

    /**
     * 模型回复结果，分别保存原始文本、校验后文本和最终回复正文。
     */
    private record ModelReply(String raw, String valid, String replyText) {
    }
}
