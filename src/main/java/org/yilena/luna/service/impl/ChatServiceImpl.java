package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.aspect.LunaLogAspect;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.ChatRequest;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.ThreeStageResponseService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.rag.models.RetrievalOptions;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.service.ChatService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.StateDrivenContextPipeline;
import org.yilena.luna.service.TaskOrchestratorService;
import org.yilena.luna.service.model.MainModelExecutionRequest;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;
import org.yilena.luna.service.model.SummaryOrchestrationResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.service.model.ToolDecisionNodeResult;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.AuthContextHolder;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
/**
 * 对话服务实现类，负责组织聊天主链路、启动收尾流程以及历史会话查询。
 */
public class ChatServiceImpl implements ChatService {

    private static final DateTimeFormatter SESSION_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");
    private final SessionService sessionService;
    private final LunaStatusPublisher statusPublisher;
    private final MemoryHotLayerService memoryHotLayerService;
    private final MemoryWritePipelineService memoryWritePipelineService;
    private final ThreeStageResponseService threeStageResponseService;
    private final RuntimeAuditService runtimeAuditService;
    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final TaskOrchestratorService taskOrchestratorService;
    private final StateDrivenContextPipeline stateDrivenContextPipeline;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatServiceImpl(SessionService sessionService,
                           LunaStatusPublisher statusPublisher,
                           MemoryHotLayerService memoryHotLayerService,
                           MemoryWritePipelineService memoryWritePipelineService,
                           ThreeStageResponseService threeStageResponseService,
                           RuntimeAuditService runtimeAuditService,
                           SessionRuntimeMapper sessionRuntimeMapper,
                           @Lazy TaskOrchestratorService taskOrchestratorService,
                           StateDrivenContextPipeline stateDrivenContextPipeline) {
        this.sessionService = sessionService;
        this.statusPublisher = statusPublisher;
        this.memoryHotLayerService = memoryHotLayerService;
        this.memoryWritePipelineService = memoryWritePipelineService;
        this.threeStageResponseService = threeStageResponseService;
        this.runtimeAuditService = runtimeAuditService;
        this.sessionRuntimeMapper = sessionRuntimeMapper;
        this.taskOrchestratorService = taskOrchestratorService;
        this.stateDrivenContextPipeline = stateDrivenContextPipeline;
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "chat")
    /**
     * 执行一轮完整对话。
     *
     * 该方法会完成输入校验、上下文治理、工具决策、回复生成、记忆写入和轮次状态落库，是聊天主流程的核心入口。
     */
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        /*
         * 第一步：提取并校验用户输入
         * 从请求对象中安全地提取用户输入内容，进行空值处理和去除首尾空格
         * 如果输入为空，直接返回400错误响应，避免后续处理链路消耗无效资源
         */
        String input = Optional.ofNullable(chatRequest)
                .map(ChatRequest::getUserInput)
                .map(String::trim)
                .orElse("");
        if (input.isEmpty()) {
            return ResponseEntity.badRequest().body("empty input");
        }

        /*
         * 发布"思考中"状态通知前端
         * 生成运行时会话ID：优先使用认证上下文中的会话ID，如果不存在则使用当前时间戳格式化生成
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);
        String runtimeSessionId = Optional.ofNullable(AuthContextHolder.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(SESSION_KEY_FORMATTER.format(LocalDateTime.now()));

        /*
         * 第二步：执行工具前的上下文治理流水线（Pre-Tool Pipeline）
         * 该阶段负责：
         * 1. 分析用户意图和任务类型
         * 2. 检索相关知识库、记忆、偏好等信息
         * 3. 重构和优化用户输入
         * 4. 确定节点工作集和执行候选资源
         * 5. 产出编排决策和结构化上下文包
         *
         * 触发源标记为"CHAT_PRE_TOOL"，表示这是聊天流程的工具前置阶段
         * runMainModel=false 表示此阶段不运行主模型生成回复
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
        RoundPipelineResult preToolPipelineResult = stateDrivenContextPipeline.run(
                StateDrivenContextPipelineRequest.builder()
                        .sessionId(runtimeSessionId)
                        .triggerSource("CHAT_PRE_TOOL")
                        .roundPipelineRequest(RoundPipelineRequest.builder()
                                .sessionId(runtimeSessionId)
                                .userInput(input)
                                .stage("CHAT_PRE_TOOL")
                                .repairSeed(input)
                                .runMainModel(false)
                                .assistantReplyOverride("")
                                .preAssemblyTriggerSource("PRE_ASSEMBLY_INPUT")
                                .postSummaryTriggerSource("CHAT_PRE_TOOL")
                                .replaceHistoryWithSummary(false)
                                .writeRoundState(false)
                                .build())
                        .build()
        );

        /*
         * 检查上下文治理流水线是否成功执行
         * 如果被阻断或结果为空，发布空闲状态并返回503服务不可用错误
         */
        if (preToolPipelineResult == null || preToolPipelineResult.isBlocked()) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.status(503).body(contextGovernanceBlockedPayload("chat pre-tool pipeline blocked"));
        }

        /*
         * 提取流水线产出的关键工件：
         * - decision: 编排决策，包含任务类型、策略选择等
         * - contextPackage: 结构化上下文包，包含各类检索结果
         * - reconstruction: 输入重构结果，优化后的用户输入
         * - nodeWorkset: 节点工作集，包含执行候选资源和MCP提示
         */
        OrchestrationDecision decision = preToolPipelineResult.getDecision();
        StructuredContextPackage contextPackage = preToolPipelineResult.getContextPackage();
        InputReconstructionResult reconstruction = preToolPipelineResult.getReconstructionResult();
        NodeWorksetResult nodeWorkset = preToolPipelineResult.getNodeWorksetResult();

        /*
         * 验证所有关键工件是否都存在
         * 任何一个缺失都表明流水线执行异常，需要终止流程
         */
        if (contextPackage == null || decision == null || reconstruction == null || nodeWorkset == null) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.status(503).body(contextGovernanceBlockedPayload("chat pre-tool pipeline artifacts missing"));
        }

        /*
         * 第三步：从上下文包中提取各类知识片段
         * 包括：任务知识、关系偏好、长期记忆、工作记忆、运行时消息等
         * 这些片段将作为后续回复生成的上下文依据
         */
        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        List<String> preferenceSnippets = extractRelationalPreferenceSnippets(contextPackage);
        List<String> longTermMemorySnippets = extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = extractWorkingMemorySnippets(contextPackage);
        List<String> runtimeMemorySnippets = extractRuntimeMessageSnippets(contextPackage);

        /*
         * 解析节点模板策略，决定如何组织和管理上下文节点
         * 提取执行候选资源列表和MCP资源提示列表
         * 提取RAG记忆片段和知识证据块
         */
        ContextNodeTemplatePolicy nodeTemplatePolicy = resolveNodeTemplatePolicy(decision, contextPackage);
        List<Resource> executionCandidates = nodeWorkset == null || nodeWorkset.getExecutionCandidates() == null
                ? List.of()
                : nodeWorkset.getExecutionCandidates();
        List<String> mcpResourceHints = nodeWorkset == null || nodeWorkset.getMcpResourceHints() == null
                ? List.of()
                : nodeWorkset.getMcpResourceHints();
        List<String> ragMemorySnippets = nodeWorkset == null || nodeWorkset.getSelectedMemorySnippets() == null
                ? List.of()
                : nodeWorkset.getSelectedMemorySnippets();
        List<EvidenceBlock> knowledgeEvidenceBlocks = nodeWorkset == null || nodeWorkset.getSelectedKnowledgeEvidenceBlocks() == null
                ? List.of()
                : nodeWorkset.getSelectedKnowledgeEvidenceBlocks();

        /*
         * 如果节点工作集中包含选定的知识片段，则覆盖默认提取的知识片段
         * 合并偏好片段，确保去重后保留所有相关偏好信息
         */
        if (nodeWorkset != null && nodeWorkset.getSelectedKnowledgeSnippets() != null && !nodeWorkset.getSelectedKnowledgeSnippets().isEmpty()) {
            knowledgeSnippets = nodeWorkset.getSelectedKnowledgeSnippets();
        }
        preferenceSnippets = mergeDistinct(
                preferenceSnippets,
                nodeWorkset == null || nodeWorkset.getSelectedPreferenceSnippets() == null
                        ? List.of()
                        : nodeWorkset.getSelectedPreferenceSnippets()
        );

        /*
         * 第四步：执行工具决策节点
         * 基于治理后的上下文，判断本轮对话是否需要调用工具
         * 产出：
         * - toolSemanticResult: 工具语义结果，描述工具的用途和参数
         * - rawToolResultChannel: 原始工具执行结果通道
         * - toolDecisionSnapshotId: 工具决策快照ID，用于追溯
         * - latestToolRawRef: 最新工具原始引用
         * - latestToolHistoryRefs: 工具历史引用列表
         * - latestToolExecutionTraces: 工具执行轨迹列表
         */
        ToolDecisionNodeResult toolDecisionNodeResult = taskOrchestratorService.orchestrateToolDecisionNode(
                runtimeSessionId,
                input,
                decision,
                contextPackage,
                reconstruction,
                nodeWorkset
        );
        String toolContext = toolDecisionNodeResult == null ? "" : stringValue(toolDecisionNodeResult.getToolContext());
        ToolSemanticResult toolSemanticResult = toolDecisionNodeResult == null ? null : toolDecisionNodeResult.getToolSemantic();
        Map<String, Object> rawToolResultChannel = toolDecisionNodeResult == null || toolDecisionNodeResult.getRawToolResultChannel() == null
                ? Map.of()
                : toolDecisionNodeResult.getRawToolResultChannel();
        String toolDecisionSnapshotId = toolDecisionNodeResult == null ? "" : stringValue(toolDecisionNodeResult.getToolDecisionSnapshotId());
        String latestToolRawRef = stringValue(rawToolResultChannel.get("latestToolRawRef"));
        @SuppressWarnings("unchecked")
        List<String> latestToolHistoryRefs = rawToolResultChannel.get("toolHistoryRefs") instanceof List<?> refs
                ? ((List<Object>) refs).stream().map(this::stringValue).filter(ref -> ref != null && !ref.isBlank()).distinct().toList()
                : List.of();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> latestToolExecutionTraces = rawToolResultChannel.get("rawToolExecutionTraces") instanceof List<?> traces
                ? ((List<Object>) traces).stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();

        /*
         * 第五步：合并工具上下文
         * 1. 生成综合摘要：将用户输入、工具上下文和结构化上下文包进行综合分析
         * 2. 合并工具语义：将工具语义结果与工具上下文融合
         * 3. 合并综合摘要：将综合摘要进一步融入工具上下文
         * 最终形成统一的mergedToolContext，作为后续回复生成的事实来源
         *
         * 同时持久化决策记录到审计日志，便于追踪合成过程
         */
        String synthesisBrief = threeStageResponseService.generateSynthesisBrief(input, toolContext, contextPackage);
        String semanticToolContext = mergeToolContextWithSemantic(toolContext, toolSemanticResult);
        String mergedToolContext = mergeToolContextWithSynthesis(semanticToolContext, synthesisBrief);
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "RESPONSE_SYNTHESIS",
                "synthesis generated",
                toJsonSafe(Map.of("synthesisBrief", synthesisBrief == null ? "" : synthesisBrief))
        );

        /*
         * 第六步：处理异步挂起状态
         * 如果工具执行处于异步挂起状态（如等待外部回调），则：
         * 1. 构建挂起回复，告知用户当前状态
         * 2. 缓存挂起的工具调用信息，便于后续恢复
         * 3. 评估记忆写入闸门，决定是否保存当前轮次的对话
         * 4. 如果允许写入，执行记忆写入操作
         * 5. 运行上下文流水线更新轮次状态，标记为挂起状态
         * 6. 返回挂起回复给前端，结束本轮交互
         *
         * 这种机制保证用户能及时看到工具执行状态，同时保持系统状态的一致性
         */
        if (isAsyncPending(mergedToolContext)) {
            String pendingReply = buildPendingReply(mergedToolContext);
            cachePendingToolCall(runtimeSessionId, mergedToolContext);
            MemoryWriteGateDecision pendingGate = evaluateMemoryWriteGate(input, pendingReply, reconstruction, toolSemanticResult, true);
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "MEMORY_WRITE_GATE",
                    pendingGate.allowWrite() ? "pending turn memory write allowed" : "pending turn memory write skipped",
                    toJsonSafe(Map.of(
                            "allowWrite", pendingGate.allowWrite(),
                            "score", pendingGate.score(),
                            "reason", pendingGate.reason(),
                            "pending", true
                    ))
            );
            if (pendingGate.allowWrite()) {
                memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, pendingReply, contextPackage);
            }
            stateDrivenContextPipeline.run(
                    StateDrivenContextPipelineRequest.builder()
                            .sessionId(runtimeSessionId)
                            .triggerSource("CHAT_TURN_PENDING")
                            .roundPipelineRequest(RoundPipelineRequest.builder()
                                    .sessionId(runtimeSessionId)
                                    .userInput(input)
                                    .decision(decision)
                                    .contextPackage(contextPackage)
                                    .reconstructionResult(reconstruction)
                                    .nodeWorksetResult(nodeWorkset)
                                    .toolSemanticResult(toolSemanticResult)
                                    .workingMemorySnippets(workingMemorySnippets)
                                    .runtimeMemorySnippets(runtimeMemorySnippets)
                                    .retrievedMemorySnippets(ragMemorySnippets)
                                    .knowledgeSnippets(knowledgeSnippets)
                                    .preferenceSnippets(preferenceSnippets)
                                    .longTermMemorySnippets(longTermMemorySnippets)
                                    .executionCandidates(executionCandidates)
                                    .mcpResourceHints(mcpResourceHints)
                                    .nodeTemplatePolicy(nodeTemplatePolicy)
                                    .toolContext(mergedToolContext)
                                    .stage("CHAT_TURN_PENDING")
                                    .repairSeed(input)
                                    .runMainModel(false)
                                    .assistantReplyOverride(pendingReply)
                                    .preAssemblyTriggerSource("PRE_ASSEMBLY_INPUT")
                                    .postSummaryTriggerSource("CHAT_TURN_PENDING")
                                    .replaceHistoryWithSummary(false)
                                    .writeRoundState(true)
                                    .latestSnapshotId(toolDecisionSnapshotId)
                                    .latestToolRawRef(latestToolRawRef)
                                    .latestToolHistoryRefs(latestToolHistoryRefs)
                                    .rawToolResultChannel(buildRawToolResultChannel(
                                            mergedToolContext,
                                            latestToolExecutionTraces,
                                            latestToolRawRef,
                                            latestToolHistoryRefs
                                    ))
                                    .retrievalPlanOverrides(Map.of(
                                            "pending", true,
                                            "nextActionHint", "await_tool_callback",
                                            "pendingRecoveryAnchor", toolDecisionSnapshotId == null ? "" : toolDecisionSnapshotId
                                    ))
                                    .build())
                            .build()
            );
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.ok(tryParseJsonNode(pendingReply));
        }

        /*
         * 第七步：主模型回复生成阶段
         * 工具链路结束后，进入正式的回复生成流程：
         * 1. 发布"思考中-整理"状态
         * 2. 保存之前的上下文状态，用于后续对比
         * 3. 构建完整的轮次流水线请求，包含所有上下文信息和配置
         * 4. 运行状态驱动的上下文流水线，生成最终回复
         *
         * 关键配置说明：
         * - runMainModel=true: 启用主模型生成回复
         * - replaceHistoryWithSummary=true: 用摘要替换历史记录以控制上下文长度
         * - writeRoundState=true: 写入轮次状态到数据库
         * - stage="CHAT_TURN": 标记为正式聊天轮次阶段
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);
        ContextState previousContextState = contextPackage == null ? null : contextPackage.getContextState();
        RoundPipelineRequest roundPipelineRequest = RoundPipelineRequest.builder()
                        .sessionId(runtimeSessionId)
                        .userInput(input)
                        .decision(decision)
                        .contextPackage(contextPackage)
                        .reconstructionResult(reconstruction)
                        .nodeWorksetResult(nodeWorkset)
                        .toolSemanticResult(toolSemanticResult)
                        .workingMemorySnippets(workingMemorySnippets)
                        .runtimeMemorySnippets(runtimeMemorySnippets)
                        .retrievedMemorySnippets(ragMemorySnippets)
                        .knowledgeSnippets(knowledgeSnippets)
                        .preferenceSnippets(preferenceSnippets)
                        .longTermMemorySnippets(longTermMemorySnippets)
                        .executionCandidates(executionCandidates)
                        .mcpResourceHints(mcpResourceHints)
                        .nodeTemplatePolicy(nodeTemplatePolicy)
                        .toolContext(mergedToolContext)
                        .stage("CHAT_TURN")
                        .repairSeed(input)
                        .runMainModel(true)
                        .assistantReplyOverride("")
                        .preAssemblyTriggerSource("PRE_ASSEMBLY_INPUT")
                        .postSummaryTriggerSource("CHAT_TURN")
                        .replaceHistoryWithSummary(true)
                        .writeRoundState(true)
                        .latestToolRawRef(latestToolRawRef)
                        .latestToolHistoryRefs(latestToolHistoryRefs)
                        .rawToolResultChannel(buildRawToolResultChannel(
                                mergedToolContext,
                                latestToolExecutionTraces,
                                latestToolRawRef,
                                latestToolHistoryRefs
                        ))
                        .build();
        RoundPipelineResult roundPipelineResult = stateDrivenContextPipeline.run(
                StateDrivenContextPipelineRequest.builder()
                        .sessionId(runtimeSessionId)
                        .triggerSource("CHAT_TURN")
                        .roundPipelineRequest(roundPipelineRequest)
                        .build()
        );

        /*
         * 提取流水线结果中的关键信息：
         * - modelResult: 主模型编排结果，包含生成的回复文本
         * - toolSemanticResult: 可能更新的工具语义结果
         * - finalSnapshotId: 最终快照ID
         * - summaryResult: 摘要结果
         *
         * 验证结果有效性：如果流水线被阻断或模型结果为空，返回503错误
         * 设置日志响应覆盖，确保审计日志记录正确的回复内容
         */
        MainModelOrchestrationResult modelResult = roundPipelineResult == null ? null : roundPipelineResult.getMainModelResult();
        toolSemanticResult = roundPipelineResult == null ? toolSemanticResult : roundPipelineResult.getToolSemanticResult();
        String finalSnapshotId = roundPipelineResult == null ? "" : stringValue(roundPipelineResult.getFinalSnapshotId());
        SummaryResult summaryResult = roundPipelineResult == null ? null : roundPipelineResult.getSummaryResult();
        if (roundPipelineResult == null || roundPipelineResult.isBlocked() || modelResult == null || modelResult.isBlocked()) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.status(503).body(contextGovernanceBlockedPayload("chat turn aborted because final governed workset is empty"));
        }
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(modelResult.getRawResponse());

        /*
         * 第八步：记忆写入和审计持久化
         * 1. 评估记忆写入闸门：基于输入质量、回复质量等因素决定是否保存对话
         * 2. 持久化闸门决策记录到审计日志
         * 3. 如果允许写入，执行记忆写入流水线，将对话保存到长期记忆
         * 4. 持久化回放和记忆治理信息，包括：
         *    - 上下文计划ID和节点ID
         *    - 前后上下文状态对比
         *    - 快照ID和摘要结果
         *    - 工具语义和输入重构结果
         *
         * 这一步确保对话可追溯，并为后续轮次提供历史依据
         */
        MemoryWriteGateDecision writeGate = evaluateMemoryWriteGate(input, modelResult.getReplyText(), reconstruction, toolSemanticResult, false);
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "MEMORY_WRITE_GATE",
                writeGate.allowWrite() ? "memory write allowed" : "memory write skipped by threshold",
                toJsonSafe(Map.of(
                        "allowWrite", writeGate.allowWrite(),
                        "score", writeGate.score(),
                        "reason", writeGate.reason(),
                        "pending", false
                ))
        );
        if (writeGate.allowWrite()) {
            memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, modelResult.getReplyText(), contextPackage);
        }
        persistReplayAndMemoryGovernance(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                previousContextState,
                finalSnapshotId,
                summaryResult,
                toolSemanticResult,
                reconstruction
        );

        /*
         * 第九步：完成本轮对话
         * 1. 发布空闲状态，通知前端对话结束
         * 2. 解析并返回模型生成的有效回复（尝试解析为JSON格式）
         *
         * 至此，一轮完整的对话流程结束，包括：
         * 输入校验 → 上下文治理 → 工具决策 → 回复生成 → 记忆写入 → 状态落库
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        return ResponseEntity.ok(tryParseJsonNode(modelResult.getValidResponse()));
    }


    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "startup")
    /**
     * 执行启动欢迎流程，并通过状态治理流水线生成启动阶段回复。
     */
    public ResponseEntity<Object> startup() {
        /**
         * 先写入启动消息和状态事件，确保会话历史与前端状态能感知到系统进入启动阶段。
         */
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "startup", LocalTime.now()));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
        String startupInput = "startup";
        RoundPipelineResult startupRound = stateDrivenContextPipeline.run(
                StateDrivenContextPipelineRequest.builder()
                        .sessionId(keyPrefix)
                        .triggerSource("STARTUP")
                        .roundPipelineRequest(RoundPipelineRequest.builder()
                                .sessionId(keyPrefix)
                                .userInput(startupInput)
                                .stage("STARTUP")
                                .repairSeed(startupInput)
                                .toolContext("")
                                .runMainModel(true)
                                .assistantReplyOverride("")
                                .preAssemblyTriggerSource("STARTUP_PRE_ASSEMBLY_INPUT")
                                .postSummaryTriggerSource("STARTUP")
                                .replaceHistoryWithSummary(false)
                                .writeRoundState(true)
                                .rawToolResultChannel(Map.of())
                                .build())
                        .build()
        );
        MainModelOrchestrationResult startupResult = startupRound == null ? null : startupRound.getMainModelResult();
        if (startupRound == null || startupRound.isBlocked() || startupResult == null || startupResult.isBlocked()) {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.status(503).body(contextGovernanceBlockedPayload("startup aborted because final governed workset is empty"));
        }
        /**
         * 复用常规轮次流水线处理启动消息，保证启动回复与普通对话共享一致的上下文治理机制。
         */
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(startupResult.getRawResponse());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, startupResult.getReplyText(), LocalTime.now()));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        return ResponseEntity.ok(tryParseJsonNode(startupResult.getValidResponse()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "shutdown")
    /**
     * 记录关闭事件，确保会话历史中保留系统收尾痕迹。
     */
    public void shutdown() {
        String keyPrefix = SESSION_KEY_FORMATTER.format(LocalDateTime.now());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "shutdown", LocalTime.now()));
    }

    @Override
    /**
     * 根据年月查询存在历史记录的日期列表，供前端按日展示历史入口。
     */
    public List<String> getHistoryDate(String yearMonth) {
        List<String> result = new ArrayList<>();
        String prefix = (yearMonth == null ? "" : yearMonth.trim()) + ":";
        if (prefix.length() < 8) {
            return result;
        }
        List<Map<String, Object>> rows = sessionRuntimeMapper.selectDistinctSessionIdsLike(prefix + "%");
        for (Map<String, Object> row : rows) {
            String sessionId = String.valueOf(row.get("session_id"));
            if (sessionId.startsWith(prefix) && sessionId.length() > prefix.length()) {
                result.add(sessionId.substring(prefix.length()));
            }
        }
        return result;
    }

    @Override
    /**
     * 查询指定日期的对话历史，并转换为简单的字符串视图返回给控制层。
     */
    public List<String> getHistory(String yearMonthDay) {
        List<ChatMessage> chats = sessionService.getRecentMessages(yearMonthDay, true);
        if (chats == null) {
            return Collections.emptyList();
        }
        return chats.stream().map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime()).toList();
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

    private JsonNode tryParseJsonNode(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
        }
        try {
            return mapper.readTree(cleaned);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isAsyncPending(String toolContext) {
        JsonNode node = tryParseJsonNode(toolContext);
        return node != null && "pending".equalsIgnoreCase(node.path("status").asText(""));
    }

    private String buildPendingReply(String toolContext) {
        try {
            JsonNode node = tryParseJsonNode(toolContext);
            String taskId = node != null ? node.path("taskId").asText("") : "";
            String workflowName = node != null
                    ? node.path("workflowName").asText(node.path("skillName").asText("task"))
                    : "task";
            ObjectNode out = mapper.createObjectNode();
            out.put("emotion", "Soft");
            out.put("reply", "Luna is processing " + workflowName + ". You can continue chatting, result will arrive soon.");
            out.put("status", "pending");
            out.put("taskId", taskId);
            out.put("workflowName", workflowName);
            return out.toString();
        } catch (Exception e) {
            return "{\"emotion\":\"Soft\",\"reply\":\"task is running in background\",\"status\":\"pending\"}";
        }
    }

    private void cachePendingToolCall(String sessionId, String toolContext) {
        JsonNode node = tryParseJsonNode(toolContext);
        if (node == null) {
            return;
        }
        String taskId = node.path("taskId").asText("");
        if (taskId.isBlank()) {
            return;
        }
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("taskId", taskId);
        String workflowName = node.path("workflowName").asText(node.path("skillName").asText(""));
        payload.put("workflowName", workflowName);
        payload.put("skillName", workflowName);
        payload.put("status", "pending");
        payload.put("toolContext", toolContext == null ? "" : toolContext);
        memoryHotLayerService.putPendingToolCall(sessionId, taskId, payload);
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
                .map(item -> "title: " + nullSafe(stringValue(item.get("title"))) + "\ncontent: " + nullSafe(stringValue(item.get("chunk_text"))))
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
            return Collections.emptyList();
        }
        Object raw = contextPackage.getTaskContext().get("working_memory");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Collections.emptyList();
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
            return Collections.emptyList();
        }
        Object raw = contextPackage.getRelationalContext().get("semantic_facts");
        if (!(raw instanceof List<?> list)) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) list;
        return rows.stream()
                .map(item -> "relation_pref: " + nullSafe(stringValue(item.get("fact_key"))) + "=" + nullSafe(stringValue(item.get("fact_value_text"))))
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
                .map(item -> nullSafe(stringValue(item.get("role"))) + ": " + nullSafe(stringValue(item.get("content_text"))))
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

    private MemoryWriteGateDecision evaluateMemoryWriteGate(String userInput,
                                                            String assistantReply,
                                                            InputReconstructionResult reconstruction,
                                                            ToolSemanticResult toolSemanticResult,
                                                            boolean pendingTurn) {
        double inputSignal = userInput == null ? 0.0 : Math.min(1.0, userInput.trim().length() / 120.0);
        double replySignal = assistantReply == null ? 0.0 : Math.min(1.0, assistantReply.trim().length() / 180.0);
        double intentSignal = reconstruction == null ? 0.0 : bounded(reconstruction.getIntentConfidence(), 0.0, 1.0);
        double semanticSignal = toolSemanticResult == null ? 0.0 : bounded(toolSemanticResult.getConfidence(), 0.0, 1.0);
        double score = bounded(inputSignal * 0.30 + replySignal * 0.25 + intentSignal * 0.25 + semanticSignal * 0.20, 0.0, 1.0);
        double threshold = pendingTurn ? 0.35 : 0.45;
        boolean allow = score >= threshold || intentSignal >= 0.60 || semanticSignal >= 0.70;
        String reason = allow ? "signal_above_threshold" : "weak_signal_low_confidence";
        return new MemoryWriteGateDecision(allow, score, reason);
    }

    private String mergeToolContextWithSynthesis(String toolContext, String synthesisBrief) {
        String brief = synthesisBrief == null ? "" : synthesisBrief.trim();
        if (brief.isEmpty()) {
            return toolContext;
        }
        try {
            JsonNode node = tryParseJsonNode(toolContext);
            if (node != null && node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                objectNode.put("three_stage_synthesis_brief", brief);
                return objectNode.toString();
            }
        } catch (Exception ignore) {
        }
        String base = toolContext == null || toolContext.isBlank() ? "{}" : toolContext;
        return base + "\n\n[THREE_STAGE_SYNTHESIS_BRIEF]\n" + brief;
    }

    private String mergeToolContextWithSemantic(String toolContext, ToolSemanticResult semanticResult) {
        if (semanticResult == null) {
            return toolContext;
        }
        try {
            JsonNode node = tryParseJsonNode(toolContext);
            ObjectNode objectNode = node != null && node.isObject() ? (ObjectNode) node : mapper.createObjectNode();
            objectNode.put("tool_semantic_status", semanticResult.getToolStatus());
            objectNode.put("tool_semantic_next_step", semanticResult.getNextStepHint());
            objectNode.put("tool_semantic_business_impact", semanticResult.getBusinessImpact());
            objectNode.put("tool_semantic_confidence", semanticResult.getConfidence());
            objectNode.set("tool_semantic_payload", mapper.valueToTree(semanticResult.getSemanticPayload()));
            return objectNode.toString();
        } catch (Exception ignore) {
            return toolContext;
        }
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
            String normalizedToolName = normalizeToolName(trace.get("tool_name"), sequence);
            String normalizedStatus = normalizeCallStatus(trace.get("call_status"));
            Map<String, Object> normalizedInput = new LinkedHashMap<>();
            normalizedInput.put("sequence", sequence);
            normalizedInput.put("source_type", stringValue(trace.get("source_type")));
            normalizedInput.put("payload", trace.getOrDefault("normalized_input", Map.of()));

            Map<String, Object> normalizedOutput = new LinkedHashMap<>();
            normalizedOutput.put("sequence", sequence);
            normalizedOutput.put("source_type", stringValue(trace.get("source_type")));
            normalizedOutput.put("payload", trace.getOrDefault("normalized_output", Map.of()));

            Long traceId = runtimeAuditService.persistToolExecutionTraceAndReturnId(
                    sessionId,
                    planId,
                    nodeId,
                    normalizedToolName,
                    normalizedStatus,
                    toJsonSafe(normalizedInput),
                    toJsonSafe(normalizedOutput),
                    stringValue(trace.get("error_message")),
                    normalizeLatency(trace.get("latency_ms"))
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

    private String normalizeCallStatus(Object rawStatus) {
        String status = stringValue(rawStatus);
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return status.toUpperCase();
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
        String normalizedStatus = callStatus == null || callStatus.isBlank() ? "UNKNOWN" : callStatus.toUpperCase();
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

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String resolveLatestToolRawResultRef(ToolTraceRefs traceRefs,
                                                 List<Map<String, Object>> toolRows,
                                                 ToolState previousToolState) {
        if (traceRefs != null && traceRefs.latestRawRef() != null && !traceRefs.latestRawRef().isBlank()) {
            return traceRefs.latestRawRef();
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

    private List<String> resolveActiveToolEvidenceRefs(ToolTraceRefs traceRefs,
                                                       List<Map<String, Object>> toolRows,
                                                       ContextState previousContextState) {
        List<String> refs = new ArrayList<>();
        if (traceRefs != null && traceRefs.historyRefs() != null && !traceRefs.historyRefs().isEmpty()) {
            refs.addAll(traceRefs.historyRefs());
        }
        if (refs.isEmpty() && toolRows != null) {
            refs.addAll(extractToolHistoryRefs(toolRows));
        }
        if (refs.isEmpty() && previousContextState != null && previousContextState.getActiveToolEvidenceRefs() != null) {
            refs.addAll(previousContextState.getActiveToolEvidenceRefs());
        }
        if (refs.isEmpty()) {
            refs.add("tool_execution_trace:latest");
        }
        return refs.stream().filter(ref -> ref != null && !ref.isBlank()).distinct().toList();
    }

    private void persistReplayAndMemoryGovernance(String sessionId,
                                                  Long planId,
                                                  Long nodeId,
                                                  ContextState previousContextState,
                                                  String latestSnapshotId,
                                                  SummaryResult summaryResult,
                                                  ToolSemanticResult toolSemanticResult,
                                                  InputReconstructionResult reconstruction) {
        String previousSnapshotId = previousContextState == null ? "" : firstNonBlank(previousContextState.getLatestContextSnapshotId(), "");
        double toolConfidence = bounded(toolSemanticResult == null ? 0.0 : toolSemanticResult.getConfidence(), 0.0, 1.0);
        double summaryConfidence = summaryResult == null || summaryResult.getStateSnapshot() == null ? 0.0 : 0.70;
        double intentConfidence = bounded(reconstruction == null ? 0.0 : reconstruction.getIntentConfidence(), 0.0, 1.0);
        double qualityScore = bounded(toolConfidence * 0.50 + summaryConfidence * 0.25 + intentConfidence * 0.25, 0.0, 1.0);
        boolean comparable = previousSnapshotId != null && !previousSnapshotId.isBlank()
                && latestSnapshotId != null && !latestSnapshotId.isBlank();

        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "QUALITY_REPLAY_COMPARISON",
                comparable ? "snapshot replay comparable" : "snapshot replay baseline missing",
                toJsonSafe(Map.of(
                        "previousSnapshotId", previousSnapshotId == null ? "" : previousSnapshotId,
                        "currentSnapshotId", latestSnapshotId == null ? "" : latestSnapshotId,
                        "comparable", comparable,
                        "qualityScore", qualityScore,
                        "toolSemanticConfidence", toolConfidence,
                        "intentConfidence", intentConfidence
                ))
        );

        boolean memoryWriteAllowed = qualityScore >= 0.45 || intentConfidence >= 0.60;
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                planId,
                nodeId,
                "MEMORY_WRITE_THRESHOLD_GOVERNANCE",
                memoryWriteAllowed ? "memory write gate passed" : "memory write gate blocked",
                toJsonSafe(Map.of(
                        "memoryWriteAllowed", memoryWriteAllowed,
                        "threshold", 0.45,
                        "qualityScore", qualityScore,
                        "intentConfidence", intentConfidence
                ))
        );
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
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

    private List<RetrievalRoute> resolveAllowedRoutes(OrchestrationDecision decision) {
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        RelationalRuntimeState relationalState = decision == null ? null : decision.getRelationalState();
        if ((taskState == TaskRuntimeState.PLANNING
                || taskState == TaskRuntimeState.REPLANNING
                || taskState == TaskRuntimeState.EXECUTING
                || taskState == TaskRuntimeState.REFLECTING)
                || relationalState == RelationalRuntimeState.DEEP_TALK
                || relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT) {
            return RetrievalRoute.all();
        }
        return List.of(RetrievalRoute.SEARCH, RetrievalRoute.NATIVE, RetrievalRoute.MODULAR);
    }

    private RetrievalOptions resolveRetrievalOptions(String input, OrchestrationDecision decision) {
        boolean debug = input != null && (input.contains("#rag_debug") || input.contains("/rag_debug"));
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

    private List<String> nonBlankList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value);
    }

    private Map<String, Object> contextGovernanceBlockedPayload(String message) {
        return Map.of(
                "status", "context_governance_blocked",
                "message", nullSafe(message)
        );
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
                + ", server=" + stringValue(first.getServerCode())
                + ", resourceUri=" + stringValue(first.getResourceUri());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String toJsonSafe(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ignore) {
            return "{}";
        }
    }

    private double bounded(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
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
     * 记忆写入门控结果，描述当前回复是否允许写入记忆及其判定原因。
     */
    private record MemoryWriteGateDecision(boolean allowWrite, double score, String reason) {
    }

}
