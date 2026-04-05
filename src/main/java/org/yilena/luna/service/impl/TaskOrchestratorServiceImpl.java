package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.constants.ModelHintConstant;
import org.yilena.luna.context.ContextAssembler;
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
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
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
import org.yilena.luna.prompt.PromptTemplates;
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
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.model.BlueprintOrchestrationResult;
import org.yilena.luna.service.model.BlueprintDraft;
import org.yilena.luna.service.model.MainModelExecutionRequest;
import org.yilena.luna.service.model.MainModelOrchestrationResult;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.RoundStateWriteRequest;
import org.yilena.luna.service.model.SummaryOrchestrationResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final ContextStateStore contextStateStore;
    private final TaskStateStore taskStateStore;
    private final RetrievalStateStore retrievalStateStore;
    private final ToolStateStore toolStateStore;
    private final ToolSemanticResultValidator toolSemanticResultValidator;
    private final LlmClientUtil llmClientUtil;
    private final GeminiProperty geminiProperty;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    @Override
    public TaskOrchestrationResult orchestrateUserInput(String sessionId, String userInput) {
        StructuredContextPackage preContextPackage = contextCompilerService.compile(sessionId, userInput, null, null);
        InputReconstructionResult reconstructionResult = inputReconstructionAgent.reconstruct(
                sessionId,
                userInput,
                preContextPackage,
                preContextPackage == null ? null : preContextPackage.getTaskState(),
                preContextPackage == null ? null : preContextPackage.getRelationalState()
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
                toJsonSafe(buildInputReconstructionAuditPayload(userInput, reconstructionResult))
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
                toJsonSafe(buildInputReconstructionAuditPayload(userInput, reconstructionResult))
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
        String worksetTraceId = buildTraceId("NODE_WORKSET", sessionId, contextPlanId(contextPackage), contextNodeId(contextPackage));
        Map<String, Object> traceMeta = buildTraceMeta(contextPackage, contextNodeId(contextPackage), worksetTraceId, "NODE_WORKSET");
        RecoveryRefreshPlan refreshPlan = consumeRecoveryRefreshPlan(contextPackage);
        String mcpDrivenInput = mcpQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
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

        String ragQuery = ragQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        String memoryQuery = memoryQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
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
            if (replaceHistory && summaryResult != null && summaryResult.getNarrativeSummary() != null
                    && !summaryResult.getNarrativeSummary().isBlank()) {
                String snapshotText = summaryResult.getStateSnapshot() == null || summaryResult.getStateSnapshot().isEmpty()
                        ? ""
                        : toJsonSafe(summaryResult.getStateSnapshot());
                sessionService.replaceHistoryWithSummary(sessionId, summaryResult.getNarrativeSummary(), snapshotText);
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
        Map<String, Object> rawToolResultChannel = request.getRawToolResultChannel() == null ? Map.of() : request.getRawToolResultChannel();
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
                buildFinalSnapshotActiveRefs(request, contextPackage)
        );
        Map<String, Object> contextTraceMeta = buildTraceMeta(
                contextPackage,
                nodeId,
                buildTraceId("MAIN_MODEL_CONTEXT", sessionId, planId, nodeId),
                "CONTEXT_ASSEMBLY"
        );
        contextTraceLogger.log(sessionId, planId, nodeId, assembledContext, contextTraceMeta);

        String finalSnapshotId = assembledContext == null ? "" : nullSafe(assembledContext.getSnapshotId());
        if (!sessionId.isBlank()) {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "CONTEXT_SNAPSHOT_FINAL",
                    "final model context snapshot persisted by context assembler",
                    toJsonSafe(Map.of("snapshotId", finalSnapshotId))
            );
        }

        AssembledContext assembledWithSnapshot = assembledContext == null
                ? null
                : AssembledContext.builder()
                .prompt(assembledContext.getPrompt())
                .sections(assembledContext.getSections())
                .candidatePool(assembledContext.getCandidatePool())
                .sectionTokenCounts(assembledContext.getSectionTokenCounts())
                .sectionTokenRatios(assembledContext.getSectionTokenRatios())
                .snapshotId(finalSnapshotId)
                .build();

        String finalPrompt = assembledWithSnapshot == null ? "" : nullSafe(assembledWithSnapshot.getPrompt());
        if (finalPrompt.isBlank()) {
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
        TaskState taskState = TaskState.builder()
                .taskId(String.valueOf(contextPlanId(contextPackage)))
                .sessionId(sessionId)
                .objective(reconstruction == null ? "" : reconstruction.getExplicitTaskGoal())
                .currentStage(request.getDecision() == null || request.getDecision().getTaskState() == null ? "UNKNOWN" : request.getDecision().getTaskState().name())
                .currentNode(String.valueOf(contextNodeId(contextPackage)))
                .confirmedSlots(confirmedSlots)
                .pendingQuestions(pendingQuestions)
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
        if (request.getRetrievalPlanOverrides() != null && !request.getRetrievalPlanOverrides().isEmpty()) {
            retrievalPlan.putAll(request.getRetrievalPlanOverrides());
        } else {
            retrievalPlan.put("allowedRoutes", resolveAllowedRoutes(request.getDecision()));
            retrievalPlan.put("maxLatencyMs", resolveRetrievalOptions(
                    buildGovernedSignal("", request.getReconstruction()),
                    request.getDecision()
            ).getMaxLatencyMs());
        }
        RetrievalState retrievalState = RetrievalState.builder()
                .reconstructedIntent(reconstruction == null ? "" : reconstruction.getNormalizedUserIntent())
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
                toolRows,
                previousToolState
        );
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
                .lastToolRawResultDigest(firstNonBlank(latestToolRawResultDigest, previousToolState == null ? "" : previousToolState.getLastToolRawResultDigest()))
                .lastToolRawResultPreview(firstNonBlank(latestToolRawResultPreview, previousToolState == null ? "" : previousToolState.getLastToolRawResultPreview()))
                .lastToolRawResultJson(firstNonBlank(limitRawJson(latestToolRawResultJson, 4096), previousToolState == null ? "" : previousToolState.getLastToolRawResultJson()))
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
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(
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

    private Map<String, Object> buildInputReconstructionAuditPayload(String rawInput, InputReconstructionResult reconstruction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String raw = nullSafe(rawInput).trim();
        payload.put("rawInput", raw);
        payload.put("rawInputLength", raw.length());
        payload.put("reconstruction", reconstruction == null ? Map.of() : reconstruction);
        payload.put("delta", buildReconstructionDelta(raw, reconstruction));
        return payload;
    }

    private Map<String, Object> buildReconstructionDelta(String rawInput, InputReconstructionResult reconstruction) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (reconstruction == null) {
            delta.put("status", "missing_reconstruction");
            delta.put("addedItems", List.of());
            delta.put("disambiguatedItems", List.of());
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
        delta.put("status", (dedupAdded.isEmpty() && dedupDisambiguated.isEmpty()) ? "no_explicit_delta" : "delta_detected");
        delta.put("addedItems", new ArrayList<>(dedupAdded));
        delta.put("disambiguatedItems", new ArrayList<>(dedupDisambiguated));
        delta.put("intentConfidence", reconstruction.getIntentConfidence());
        return delta;
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
        boolean waitingResumeState = taskState == TaskRuntimeState.WAITING_APPROVAL
                || taskState == TaskRuntimeState.WAITING_TOOL
                || taskState == TaskRuntimeState.WAITING_USER;
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
            if (!previousEvent.isBlank() && containsAny(previousReason.toLowerCase(Locale.ROOT),
                    "approval", "tool", "interrupt", "timeout", "failed")) {
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
            row.put("rank", rank++);
            row.put("id", nullSafe(evidence.getId()));
            row.put("source", evidence.getSource() == null ? "" : evidence.getSource().name());
            row.put("role", evidence.getRole() == null ? "" : evidence.getRole().name());
            row.put("score", evidence.getScore());
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
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("capabilityName", stringValue(candidate.get("capability_name")));
            row.put("capabilityType", stringValue(candidate.get("capability_type")));
            row.put("serverCode", stringValue(candidate.get("server_code")));
            row.put("score", firstNonBlank(
                    stringValue(candidate.get("score")),
                    firstNonBlank(
                            stringValue(candidate.get("final_score")),
                            stringValue(candidate.get("relevance_score"))
                    )
            ));
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
                                                  List<Map<String, Object>> toolRows,
                                                  ToolState previousToolState) {
        String fromChannel = extractLatestRawResultFromChannel(rawToolResultChannel);
        if (!fromChannel.isBlank()) {
            return fromChannel;
        }
        if (toolRows != null && !toolRows.isEmpty()) {
            String normalizedOutput = stringValue(toolRows.get(0).get("normalized_output"));
            if (!normalizedOutput.isBlank()) {
                return normalizedOutput;
            }
        }
        if (previousToolState != null && previousToolState.getLastToolRawResultJson() != null
                && !previousToolState.getLastToolRawResultJson().isBlank()) {
            return previousToolState.getLastToolRawResultJson();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractLatestRawResultFromChannel(Map<String, Object> rawToolResultChannel) {
        if (rawToolResultChannel == null || rawToolResultChannel.isEmpty()) {
            return "";
        }
        Object tracesObj = rawToolResultChannel.get("rawToolExecutionTraces");
        if (!(tracesObj instanceof List<?> traces) || traces.isEmpty()) {
            return "";
        }
        Object latest = traces.get(0);
        if (!(latest instanceof Map<?, ?> latestMap)) {
            return "";
        }
        Object rawOutput = latestMap.get("normalized_output");
        if (rawOutput == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(rawOutput);
        } catch (Exception ignore) {
            return String.valueOf(rawOutput);
        }
    }

    private String limitRawJson(String raw, int maxLen) {
        String normalized = raw == null ? "" : raw;
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen));
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
                .explicitTaskGoal(nullSafe(reconstructionResult.getExplicitTaskGoal()))
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
        overrides.put("blueprintEntry", true);
        overrides.put("blueprintDraftReady", draft != null);
        if (draft != null) {
            overrides.put("blueprintDraft", objectMapper.convertValue(draft, Map.class));
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
