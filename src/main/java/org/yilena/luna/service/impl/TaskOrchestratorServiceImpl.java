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

    @Override
    public TaskOrchestrationResult orchestrateUserInput(String sessionId, String userInput) {
        String transitionTraceId = buildTraceId("TASK_ORCHESTRATOR", sessionId, null, null);
        StructuredContextPackage preContextPackage = contextCompilerService.compile(sessionId, userInput, null, null);
        InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                sessionId,
                userInput,
                preContextPackage,
                preContextPackage == null ? null : preContextPackage.getTaskState(),
                preContextPackage == null ? null : preContextPackage.getRelationalState()
        );
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
        GovernedSignal governedSignal = buildGovernedSignal(userInput, reconstructionResult);
        OrchestrationDecision decision = eventIngressService.ingestUserInput(
                sessionId,
                userInput,
                toJsonSafe(governedSignal)
        );
        StructuredContextPackage contextPackage = decision == null ? preContextPackage : decision.getContextPackage();
        RecoveryTrigger recoveryTrigger = resolveRecoveryTrigger(userInput, decision, contextPackage);
        if (recoveryTrigger.shouldRecover) {
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
            contextPackage = recoveryContextAgent.recover(
                    sessionId,
                    contextPackage,
                    recoveryTrigger.recoveryEvent,
                    recoveryTrigger.interruptReason
            );
            if (shouldRunImmediateRecoveryRefresh(contextPackage, reconstructionResult)) {
                NodeWorksetResult refreshedWorkset = orchestrateNodeWorkset(
                        sessionId,
                        userInput,
                        decision,
                        contextPackage,
                        reconstructionResult
                );
                contextPackage = applyImmediateRecoveryRefreshResult(contextPackage, reconstructionResult, refreshedWorkset);
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
            if (!hasPendingRecoveryWork(contextPackage)) {
                recoveryStateStore.clear(sessionId);
            }
        } else {
            recoveryStateStore.clear(sessionId);
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_SKIPPED",
                    "normal chat turn without interrupt/resume event",
                    toJsonSafe(Map.of("input", userInput == null ? "" : userInput))
            );
        }
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
        runtimeAuditService.persistContextSnapshot(sessionId, contextPackage);
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected by reconstructed input signal",
                toJsonSafe(buildDecisionStatePayload(decision))
        );
        runtimeAuditService.persistDecisionRecord(
                sessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "INPUT_RECONSTRUCTION",
                "input reconstructed before RAG/MCP routing",
                toJsonSafe(buildInputReconstructionAuditPayload(userInput, reconstructionResult, contextPackage))
        );
        return TaskOrchestrationResult.builder()
                .decision(decision)
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .recovered(recoveryTrigger.shouldRecover)
                .recoveryEvent(recoveryTrigger.recoveryEvent)
                .interruptReason(recoveryTrigger.interruptReason)
                .build();
    }

    @Override
    public TaskOrchestrationResult orchestrateSystemRecovery(String sessionId,
                                                             String userInput,
                                                             String eventType,
                                                             Map<String, Object> eventPayload,
                                                             String recoveryEvent,
                                                             String interruptReason) {
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
        ReconstructionRecallGate recallGate = evaluateReconstructionRecallGate(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        if (!recallGate.ready()) {
            String reason = recallGate.blockedReason();
            persistReconstructionBlockedState(sessionId, decision, contextPackage, reconstructionResult, reason);
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
        String worksetTraceId = buildTraceId("NODE_WORKSET", sessionId, contextPlanId(contextPackage), contextNodeId(contextPackage));
        Map<String, Object> traceMeta = buildTraceMeta(contextPackage, contextNodeId(contextPackage), worksetTraceId, "NODE_WORKSET");
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
        RecoveryRefreshPlan refreshPlan = consumeRecoveryRefreshPlan(contextPackage);
        String mcpDrivenInput = mcpQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
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
        if (refreshPlan.reassembleNow) {
            mcpDrivenInput = appendRefreshFlag(mcpDrivenInput, "reassembly");
        }
        if (refreshPlan.refreshMcpNow) {
            mcpDrivenInput = appendRefreshFlag(mcpDrivenInput, "mcp");
        }
        List<Map<String, Object>> rawMcpCandidates = capabilityPolicyRouterService.routeForContext(
                sessionId,
                mcpDrivenInput,
                decision == null ? null : decision.getTaskState(),
                decision == null ? null : decision.getRelationalState(),
                24
        );
        rawMcpCandidates = filterInvalidatedCapabilities(rawMcpCandidates, refreshPlan.invalidatedCapabilityNames);
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

        String ragQuery = ragQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        String memoryQuery = memoryQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
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
        if (refreshPlan.refreshRagNow || refreshPlan.reassembleNow) {
            ragQuery = appendRefreshFlag(ragQuery, "rag");
            memoryQuery = appendRefreshFlag(memoryQuery, "memory");
        }
        List<EvidenceBlock> selectedKnowledgeEvidenceBlocks = List.of();
        List<String> selectedKnowledge = List.of();
        List<String> selectedMemory = List.of();
        List<String> selectedPreference = List.of();
        ContextRerankResult rerankResult = null;
        try {
            List<ConversationMessage> conversationContext = buildRetrievalConversationContext(contextPackage);
            List<RetrievalRoute> allowedRoutes = resolveAllowedRoutes(decision);
            if (refreshPlan.refreshRagNow || refreshPlan.reassembleNow) {
                allowedRoutes = RetrievalRoute.all();
            }
            GovernedSignal governedSignal = buildGovernedSignal(userInput, reconstructionResult);
            RetrievalOptions options = resolveRetrievalOptions(governedSignal, decision);
            if (refreshPlan.refreshRagNow || refreshPlan.reassembleNow) {
                options = RetrievalOptions.builder()
                        .debug(options.isDebug())
                        .maxLatencyMs(Math.max(options.getMaxLatencyMs(), 1800L))
                        .build();
            }
            RetrievalRequest request = RetrievalRequest.builder()
                    .query(ragQuery)
                    .sessionId(sessionId)
                    .conversationContext(conversationContext)
                    .allowedRoutes(allowedRoutes)
                    .sourceScope(List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.PREFERENCE))
                    .options(options)
                    .build();
            RetrievalResponse ragResponse = retrievalService.retrieve(request);
            RetrievalRequest memoryRequest = RetrievalRequest.builder()
                    .query(memoryQuery)
                    .sessionId(sessionId)
                    .conversationContext(conversationContext)
                    .allowedRoutes(allowedRoutes)
                    .sourceScope(List.of(RetrievalSource.MEMORY))
                    .options(options)
                    .build();
            RetrievalResponse memoryResponse = retrievalService.retrieve(memoryRequest);
            RetrievalResponse response = mergeRetrievalResponses(ragResponse, memoryResponse);
            response = filterInvalidatedEvidences(response, refreshPlan.invalidatedEvidenceRefs);
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
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "MULTI_ROUTE_RECALL_TRACE",
                    "raw multi-route retrieval candidates before global rerank",
                    toJsonSafe(withTraceMeta(recallTracePayload, traceMeta, "MULTI_ROUTE_RECALL", contextNodeId(contextPackage)))
            );
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
            rerankResult = globalContextRerankAgent.rerank(
                    reconstructionResult,
                    contextPackage,
                    response,
                    mcpPreRankedCandidates,
                    decision == null ? null : decision.getTaskState()
            );
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "GLOBAL_CONTEXT_RERANK",
                    "cross-source rerank after retrieval",
                    toJsonSafe(withTraceMeta(new LinkedHashMap<>(Map.of("result", rerankResult == null ? Map.of() : rerankResult)), traceMeta, "GLOBAL_RERANK", contextNodeId(contextPackage)))
            );
            rerankTraceLogger.log(sessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), rerankResult, traceMeta);

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
            List<String> mergedMemory = new ArrayList<>(toMemorySnippets(response));
            if (rerankResult != null && rerankResult.getSelectedMemoryHints() != null) {
                mergedMemory.addAll(rerankResult.getSelectedMemoryHints());
            }
            selectedMemory = mergedMemory;
            selectedPreference = toPreferenceSnippets(response);
        } catch (Exception e) {
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

        List<Resource> executionCandidates = resolveExecutionCandidates(
                rerankResult,
                refreshPlan.reassembleNow ? List.of() : mcpPreRankedCandidates
        );
        List<String> mcpResourceHints = mcpResourceHintExtractor.extract(
                rerankResult == null ? List.of() : rerankResult.getSelectedPromptCandidates(),
                rerankResult == null ? List.of() : rerankResult.getSelectedResourceCandidates(),
                rerankResult == null ? List.of() : rerankResult.getSelectedWorkflowCandidates(),
                rerankResult == null ? List.of() : rerankResult.getSelectedToolCandidates(),
                8
        );
        List<String> selectedToolCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedToolCandidates());
        List<String> selectedPromptCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedPromptCandidates());
        List<String> selectedResourceCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedResourceCandidates());
        List<String> selectedWorkflowCandidateNames = extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedWorkflowCandidates());
        List<String> selectedPromptResourceNames = mergeDistinct(
                mergeDistinct(selectedPromptCandidateNames, selectedResourceCandidateNames),
                selectedWorkflowCandidateNames
        );
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

    @Override
    public ToolDecisionNodeResult orchestrateToolDecisionNode(String sessionId,
                                                              String userInput,
                                                              OrchestrationDecision decision,
                                                              StructuredContextPackage contextPackage,
                                                              InputReconstructionResult reconstructionResult,
                                                              NodeWorksetResult nodeWorksetResult) {
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
        ContextNodeTemplatePolicy nodeTemplatePolicy = resolveNodeTemplatePolicy(decision, contextPackage);
        List<String> memorySnippets = buildNodeScopedMemorySnippets(
                nodeTemplatePolicy,
                workingMemorySnippets,
                runtimeMemorySnippets,
                ragMemorySnippets,
                longTermMemorySnippets
        );
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
        persistImmediateToolSemanticState(
                safeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                contextPackage,
                toolSemanticResult,
                rawToolResultChannel,
                toolTraceRefs.historyRefs()
        );
        return ToolDecisionNodeResult.builder()
                .toolContext(toolContext)
                .rawToolResultChannel(rawToolResultChannel)
                .toolTraceRefs(toolTraceRefs.historyRefs())
                .toolSemantic(toolSemanticResult)
                .preToolSnapshotId(preToolSnapshotId == null ? "" : preToolSnapshotId)
                .toolDecisionSnapshotId(toolDecisionSnapshotId == null ? "" : toolDecisionSnapshotId)
                .build();
    }

    @Override
    public BlueprintOrchestrationResult orchestrateBlueprintInput(String sessionId, String userGoal) {
        TaskOrchestrationResult orchestrationResult = orchestrateUserInput(sessionId, userGoal);
        StructuredContextPackage contextPackage = orchestrationResult == null ? null : orchestrationResult.getContextPackage();
        InputReconstructionResult reconstructionResult = orchestrationResult == null ? null : orchestrationResult.getReconstructionResult();
        OrchestrationDecision decision = orchestrationResult == null ? null : orchestrationResult.getDecision();
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
        if (sessionId != null && !sessionId.isBlank()) {
            try {
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
        return BlueprintOrchestrationResult.builder()
                .contextPackage(contextPackage)
                .reconstructionResult(reconstructionResult)
                .decision(decision)
                .nodeWorksetResult(nodeWorksetResult)
                .blueprintDraft(blueprintDraft)
                .build();
    }

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

    @Override
    public MainModelOrchestrationResult orchestrateMainModel(MainModelExecutionRequest request) {
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
        PromptResolveResult mainPromptResolveResult = resolveMainModelPromptAssembly(
                request.getUserInput(),
                contextPackage,
                request.getNodeTemplatePolicy()
        );
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

        ModelReply modelReply = invokeMainModel(
                finalPrompt,
                request.getRepairSeed() == null ? request.getUserInput() : request.getRepairSeed(),
                contextPackage
        );
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
        if (!sessionId.isBlank()) {
            persistPromptSnapshotRefs(sessionId, resolveRoundId(contextPackage), nodeId, finalSnapshotId, assembledContext);
        }
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

    private ModelReply invokeMainModel(String prompt, String repairSeed, StructuredContextPackage contextPackage) {
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
                String repairTemplate = resolveRepairPrompt(repairSeed, contextPackage);
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

    private String resolveRepairPrompt(String repairSeed, StructuredContextPackage contextPackage) {
        if (promptResolverService != null) {
            try {
                PromptResolveResult resolved = promptResolverService.resolve(PromptResolveContext.builder()
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

    private boolean shouldRunImmediateRecoveryRefresh(StructuredContextPackage contextPackage,
                                                      InputReconstructionResult reconstructionResult) {
        if (contextPackage == null || contextPackage.getRetrievalState() == null) {
            return false;
        }
        Map<String, Object> plan = contextPackage.getRetrievalState().getRetrievalPlan();
        if (plan == null || plan.isEmpty()) {
            return false;
        }
        boolean refreshRagNow = booleanValue(plan.get("refresh_rag_now"))
                || booleanValue(plan.get("refreshRagNow"))
                || booleanValue(plan.get("need_rag_refresh"));
        boolean refreshMcpNow = booleanValue(plan.get("refresh_mcp_now"))
                || booleanValue(plan.get("refreshMcpNow"))
                || booleanValue(plan.get("need_mcp_refresh"));
        boolean reassembleNow = booleanValue(plan.get("reassemble_now"))
                || booleanValue(plan.get("reassembleNow"))
                || booleanValue(plan.get("need_reassembly"));
        if (!(refreshRagNow || refreshMcpNow || reassembleNow)) {
            return false;
        }
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

    private record RecallThreshold(double minIntentConfidence, int maxMissingSlots, int requiredEntities) {
    }

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

    private RecoveryTrigger resolveRecoveryTrigger(String input,
                                                   OrchestrationDecision decision,
                                                   StructuredContextPackage contextPackage) {
        String normalizedInput = nullSafe(input).trim().toLowerCase(Locale.ROOT);
        TaskRuntimeState taskState = decision == null ? null : decision.getTaskState();
        if (taskState == null && contextPackage != null) {
            taskState = contextPackage.getTaskState();
        }
        boolean waitingResumeState = taskState == TaskRuntimeState.WAITING_APPROVAL
                || taskState == TaskRuntimeState.WAITING_TOOL
                || taskState == TaskRuntimeState.WAITING_USER;
        Map<String, Object> promptPolicy = contextPackage == null ? Map.of() : safeMap(contextPackage.getPromptPolicy());
        Map<String, Object> retrievalPlan = contextPackage == null || contextPackage.getRetrievalState() == null
                ? Map.of()
                : safeMap(contextPackage.getRetrievalState().getRetrievalPlan());
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
        boolean explicitResume = containsAny(normalizedInput,
                "resume", "continue", "批准", "通过", "恢复", "继续", "确认", "approve", "confirmed");
        boolean explicitRetry = containsAny(normalizedInput, "retry", "重试", "再试", "重新执行");
        boolean explicitInterruptEvent = containsAny(normalizedInput, "callback", "tool result", "审批结果", "approval result");
        if (waitingResumeState && explicitResume) {
            return new RecoveryTrigger(true, "RESUME_REQUEST", "USER_RESUME_SIGNAL");
        }
        if (waitingResumeState && explicitRetry) {
            return new RecoveryTrigger(true, "RESUME_REQUEST", "USER_RETRY_SIGNAL");
        }
        if (explicitInterruptEvent) {
            return new RecoveryTrigger(true, "EXTERNAL_EVENT", "EVENT_CALLBACK_SIGNAL");
        }
        if (contextPackage != null && contextPackage.getRecoveryState() != null) {
            String previousEvent = nullSafe(contextPackage.getRecoveryState().getRecoveryEvent());
            String previousReason = nullSafe(contextPackage.getRecoveryState().getInterruptReason());
            if (!previousEvent.isBlank() && waitingResumeState) {
                return new RecoveryTrigger(true, previousEvent, previousReason);
            }
        }
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

    private BlueprintDraft buildBlueprintDraft(InputReconstructionResult reconstructionResult,
                                               StructuredContextPackage contextPackage,
                                               NodeWorksetResult nodeWorksetResult,
                                               OrchestrationDecision decision) {
        if (reconstructionResult == null) {
            return null;
        }
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
                return;
            }
            List<ResolvedPromptItem> items = new ArrayList<>();
            for (Object ref : refs) {
                if (!(ref instanceof Map<?, ?> row)) {
                    continue;
                }
                items.add(ResolvedPromptItem.builder()
                        .itemId(toLong(row.get("itemId")))
                        .versionId(toLong(row.get("versionId")))
                        .key(stringValue(row.get("key")))
                        .value(stringValue(row.get("value")))
                        .version(stringValue(row.get("version")))
                        .runtimeSlot(stringValue(row.get("runtimeSlot")))
                        .matchReason(stringValue(row.get("matchReason")))
                        .category(stringValue(row.get("category")))
                        .assemblerVersion(firstNonBlank(stringValue(row.get("assemblerVersion")), assemblerVersion))
                        .build());
            }
            if (items.isEmpty()) {
                return;
            }
            Map<String, List<ResolvedPromptItem>> slotMapping = parseSnapshotSlotMapping(
                    meta.get("slotMapping"),
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
            // must not break main model flow
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
                        .itemId(toLong(row.get("itemId")))
                        .versionId(toLong(row.get("versionId")))
                        .key(stringValue(row.get("key")))
                        .value(stringValue(row.get("value")))
                        .version(stringValue(row.get("version")))
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

    private record ToolTraceRefs(String latestRawRef, List<String> historyRefs) {
        private static ToolTraceRefs empty() {
            return new ToolTraceRefs("", List.of());
        }
    }

    private record RecoveryTrigger(boolean shouldRecover, String recoveryEvent, String interruptReason) {
    }

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

    private record ScoredRef(String ref, double score, boolean seenThisRound) {
    }

    private record ActiveRefGovernanceResult(List<String> refs, Map<String, Integer> ageByRef) {
    }

    private record ModelReply(String raw, String valid, String replyText) {
    }
}
