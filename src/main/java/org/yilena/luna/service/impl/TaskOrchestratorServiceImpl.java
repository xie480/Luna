package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
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
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.state.store.RecoveryStateStore;

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
        OrchestrationDecision decision = eventIngressService.ingestUserInput(
                sessionId,
                userInput,
                buildOrchestrationSignal(userInput, reconstructionResult)
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
        RecoveryRefreshPlan refreshPlan = consumeRecoveryRefreshPlan(contextPackage);
        String mcpDrivenInput = mcpQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        if (refreshPlan.needReassembly) {
            mcpDrivenInput = appendRefreshFlag(mcpDrivenInput, "reassembly");
        }
        if (refreshPlan.needMcpRefresh) {
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
                toJsonSafe(Map.of(
                        "query", mcpDrivenInput,
                        "rawCandidateCount", rawMcpCandidates.size(),
                        "candidateCount", mcpPreRankedCandidates.size(),
                        "candidates", mcpPreRankedCandidates
                ))
        );

        String ragQuery = ragQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        String memoryQuery = memoryQueryBuilder.build(
                reconstructionResult,
                decision == null ? null : decision.getTaskState()
        );
        if (refreshPlan.needRagRefresh || refreshPlan.needReassembly) {
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
            if (refreshPlan.needRagRefresh || refreshPlan.needReassembly) {
                allowedRoutes = RetrievalRoute.all();
            }
            RetrievalOptions options = resolveRetrievalOptions(userInput, decision);
            if (refreshPlan.needRagRefresh || refreshPlan.needReassembly) {
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
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "MULTI_ROUTE_RECALL_TRACE",
                    "raw multi-route retrieval candidates before global rerank",
                    toJsonSafe(Map.of(
                            "ragQuery", ragQuery,
                            "memoryQuery", memoryQuery,
                            "mcpQuery", mcpDrivenInput,
                            "allowedRoutes", allowedRoutes,
                            "knowledgeCandidates", getEvidences(ragResponse, RetrievalSource.KNOWLEDGE),
                            "memoryCandidates", getEvidences(memoryResponse, RetrievalSource.MEMORY),
                            "preferenceCandidates", getEvidences(ragResponse, RetrievalSource.PREFERENCE),
                            "mcpPreRankCandidates", mcpPreRankedCandidates,
                            "recoveryRefreshPlan", Map.of(
                                    "needRagRefresh", refreshPlan.needRagRefresh,
                                    "needMcpRefresh", refreshPlan.needMcpRefresh,
                                    "needReassembly", refreshPlan.needReassembly,
                                    "invalidatedEvidenceRefs", refreshPlan.invalidatedEvidenceRefs,
                                    "invalidatedCapabilityNames", refreshPlan.invalidatedCapabilityNames,
                                    "invalidationReasonsByRef", refreshPlan.invalidationReasonsByRef
                            )
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
                    "BOTTOM_RERANK_DETAIL_TRACE",
                    "bottom rerank detail before global semantic rerank merge",
                    toJsonSafe(Map.of(
                            "knowledgeTopIds", getEvidences(response, RetrievalSource.KNOWLEDGE).stream().limit(20).map(Evidence::getId).toList(),
                            "memoryTopIds", getEvidences(response, RetrievalSource.MEMORY).stream().limit(20).map(Evidence::getId).toList(),
                            "preferenceTopIds", getEvidences(response, RetrievalSource.PREFERENCE).stream().limit(20).map(Evidence::getId).toList(),
                            "mcpPreRankTopNames", mcpPreRankedCandidates.stream().limit(20).map(row -> stringValue(row.get("capability_name"))).toList()
                    ))
            );
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "GLOBAL_CONTEXT_RERANK",
                    "cross-source rerank after retrieval",
                    toJsonSafe(rerankResult)
            );
            rerankTraceLogger.log(sessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), rerankResult);

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
                refreshPlan.needReassembly ? List.of() : mcpPreRankedCandidates
        );
        List<String> mcpResourceHints = mcpResourceHintExtractor.extract(
                rerankResult == null ? List.of() : rerankResult.getSelectedPromptResources(),
                8
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
                .selectedToolCandidateNames(extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedToolCandidates()))
                .selectedPromptResourceNames(extractCapabilityNames(rerankResult == null ? List.of() : rerankResult.getSelectedPromptResources()))
                .invalidatedEvidenceRefs(refreshPlan.invalidatedEvidenceRefs)
                .invalidatedCapabilityNames(refreshPlan.invalidatedCapabilityNames)
                .invalidationReasonsByRef(refreshPlan.invalidationReasonsByRef)
                .executionCandidates(executionCandidates)
                .mcpResourceHints(mcpResourceHints)
                .build();
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
        boolean needRagRefresh = booleanValue(retrievalPlan.get("need_rag_refresh"));
        boolean needMcpRefresh = booleanValue(retrievalPlan.get("need_mcp_refresh"));
        boolean needReassembly = booleanValue(retrievalPlan.get("need_reassembly"));
        List<String> invalidatedEvidenceRefs = toStringList(retrievalPlan.get("invalidated_evidence_refs"));
        List<String> invalidatedCapabilityNames = toStringList(retrievalPlan.get("invalidated_capability_names"));
        Map<String, String> invalidationReasonsByRef = safeStringMap(retrievalPlan.get("invalidation_reasons_by_ref"));
        if (!needRagRefresh && !needMcpRefresh && !needReassembly) {
            if ((invalidatedEvidenceRefs == null || invalidatedEvidenceRefs.isEmpty())
                    && (invalidatedCapabilityNames == null || invalidatedCapabilityNames.isEmpty())) {
                return RecoveryRefreshPlan.empty();
            }
        }
        Map<String, Object> consumed = new LinkedHashMap<>(retrievalPlan);
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
                needRagRefresh,
                needMcpRefresh,
                needReassembly,
                invalidatedEvidenceRefs == null ? List.of() : invalidatedEvidenceRefs,
                invalidatedCapabilityNames == null ? List.of() : invalidatedCapabilityNames,
                invalidationReasonsByRef == null ? Map.of() : invalidationReasonsByRef
        );
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
                || booleanValue(retrievalPlan.get("need_reassembly"));
    }

    private String appendRefreshFlag(String query, String source) {
        String base = nullSafe(query).trim();
        if (base.isBlank()) {
            base = "recovery refresh";
        }
        return base + " [recovery_refresh=" + source + "]";
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

    private String buildOrchestrationSignal(String rawInput, InputReconstructionResult reconstruction) {
        String normalizedIntent = reconstruction == null ? "" : nullSafe(reconstruction.getNormalizedUserIntent());
        String explicitGoal = reconstruction == null ? "" : nullSafe(reconstruction.getExplicitTaskGoal());
        String timeScope = reconstruction == null ? "" : nullSafe(reconstruction.getTimeScope());
        List<String> constraints = reconstruction == null || reconstruction.getBusinessConstraints() == null
                ? List.of()
                : reconstruction.getBusinessConstraints();
        List<String> missingSlots = reconstruction == null || reconstruction.getMissingSlots() == null
                ? List.of()
                : reconstruction.getMissingSlots();
        StringBuilder signal = new StringBuilder();
        signal.append("intent=").append(normalizedIntent.isBlank() ? "intent_unavailable" : normalizedIntent);
        signal.append(";goal=").append(explicitGoal.isBlank() ? "goal_unavailable" : explicitGoal);
        signal.append(";timeScope=").append(timeScope.isBlank() ? "unspecified" : timeScope);
        signal.append(";constraints=").append(constraints);
        signal.append(";missingSlots=").append(missingSlots);
        if (reconstruction == null) {
            signal.append(";fallback=reconstruction_missing");
            signal.append(";rawInputPresent=").append(rawInput != null && !rawInput.isBlank());
            signal.append(";rawInputLength=").append(rawInput == null ? 0 : rawInput.trim().length());
        } else if (normalizedIntent.isBlank() || explicitGoal.isBlank()) {
            signal.append(";fallback=reconstruction_partial");
        } else {
            signal.append(";fallback=none");
        }
        return signal.toString();
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

    private List<Evidence> getEvidences(RetrievalResponse response, RetrievalSource source) {
        if (response == null || response.getEvidences() == null) {
            return Collections.emptyList();
        }
        return response.getEvidences().getOrDefault(source, Collections.emptyList());
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

    private List<Resource> resolveExecutionCandidates(ContextRerankResult rerankResult, List<Map<String, Object>> mcpPreRankedCandidates) {
        List<Map<String, Object>> selected = new ArrayList<>();
        if (rerankResult != null && rerankResult.getSelectedToolCandidates() != null) {
            selected.addAll(rerankResult.getSelectedToolCandidates());
        }
        if (rerankResult != null && rerankResult.getSelectedPromptResources() != null) {
            selected.addAll(rerankResult.getSelectedPromptResources());
        }
        if (selected.isEmpty() && mcpPreRankedCandidates != null) {
            selected.addAll(mcpPreRankedCandidates);
        }
        return toolRouter.materializeCandidates(selected, 16);
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

    private record RecoveryRefreshPlan(boolean needRagRefresh,
                                       boolean needMcpRefresh,
                                       boolean needReassembly,
                                       List<String> invalidatedEvidenceRefs,
                                       List<String> invalidatedCapabilityNames,
                                       Map<String, String> invalidationReasonsByRef) {
        private static RecoveryRefreshPlan empty() {
            return new RecoveryRefreshPlan(false, false, false, List.of(), List.of(), Map.of());
        }
    }
}
