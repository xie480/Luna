package org.yilena.luna.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.aspect.LunaLogAspect;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.constants.ModelHintConstant;
import org.yilena.luna.constants.RedisKeyConstant;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.EvidenceBlockBuilder;
import org.yilena.luna.context.GlobalContextRerankAgent;
import org.yilena.luna.context.InputReconstructionAgent;
import org.yilena.luna.context.McpCandidatePreRank;
import org.yilena.luna.context.McpResourceHintExtractor;
import org.yilena.luna.context.McpQueryBuilder;
import org.yilena.luna.context.RagQueryBuilder;
import org.yilena.luna.context.RecoveryContextAgent;
import org.yilena.luna.context.RerankTraceLogger;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.SummaryTraceLogger;
import org.yilena.luna.context.ToolSemanticAgent;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.ToolSemanticTraceLogger;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.ChatMessage;
import org.yilena.luna.entity.ChatRequest;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.entity.ToolCallingContext;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.ThreeStageResponseService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.mapper.SessionRuntimeMapper;
import org.yilena.luna.prompt.PromptAssembler;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.rag.api.RetrievalService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.ConversationMessage;
import org.yilena.luna.rag.models.RetrievalOptions;
import org.yilena.luna.rag.models.RetrievalRequest;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.service.AgentService;
import org.yilena.luna.service.ChatService;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.ContextSnapshotStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.ToolStateStore;
import org.yilena.luna.sse.LunaStatusPublisher;
import org.yilena.luna.utils.AuthContextHolder;
import org.yilena.luna.utils.LlmClientUtil;
import org.yilena.luna.utils.ToolCallingContextHolder;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final DateTimeFormatter SESSION_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private final PromptAssembler promptAssembler;
    private final SessionService sessionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final GeminiProperty geminiProperty;
    private final LlmClientUtil llmClientUtil;
    private final LunaStatusPublisher statusPublisher;
    private final AgentService agentService;
    private final RetrievalService retrievalService;
    private final EventIngressService eventIngressService;
    private final MemoryHotLayerService memoryHotLayerService;
    private final MemoryWritePipelineService memoryWritePipelineService;
    private final ThreeStageResponseService threeStageResponseService;
    private final RuntimeAuditService runtimeAuditService;
    private final SessionRuntimeMapper sessionRuntimeMapper;
    private final InputReconstructionAgent inputReconstructionAgent;
    private final ContextCompilerService contextCompilerService;
    private final RecoveryContextAgent recoveryContextAgent;
    private final RagQueryBuilder ragQueryBuilder;
    private final McpQueryBuilder mcpQueryBuilder;
    private final McpCandidatePreRank mcpCandidatePreRank;
    private final McpResourceHintExtractor mcpResourceHintExtractor;
    private final GlobalContextRerankAgent globalContextRerankAgent;
    private final ToolSemanticAgent toolSemanticAgent;
    private final ToolSemanticResultValidator toolSemanticResultValidator;
    private final SummaryAgent summaryAgent;
    private final ContextAssembler contextAssembler;
    private final ToolRouter toolRouter;
    private final EvidenceBlockBuilder evidenceBlockBuilder;
    private final ContextTraceLogger contextTraceLogger;
    private final RerankTraceLogger rerankTraceLogger;
    private final SummaryTraceLogger summaryTraceLogger;
    private final ToolSemanticTraceLogger toolSemanticTraceLogger;
    private final TaskStateStore taskStateStore;
    private final RetrievalStateStore retrievalStateStore;
    private final ToolStateStore toolStateStore;
    private final ContextStateStore contextStateStore;
    private final ContextSnapshotStore contextSnapshotStore;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @LunaLogRecord(module = LogModuleConstant.CHAT, action = LogActionConstant.CHAT, type = LogType.LUNA_OUTPUT, content = "chat")
    public ResponseEntity<Object> chat(ChatRequest chatRequest) {
        String input = Optional.ofNullable(chatRequest)
                .map(ChatRequest::getUserInput)
                .map(String::trim)
                .orElse("");
        if (input.isEmpty()) {
            return ResponseEntity.badRequest().body("empty input");
        }

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING);
        String runtimeSessionId = Optional.ofNullable(AuthContextHolder.getSessionId())
                .filter(s -> !s.isBlank())
                .orElse(SESSION_KEY_FORMATTER.format(LocalDateTime.now()));

        StructuredContextPackage preContextPackage = contextCompilerService.compile(runtimeSessionId, input, null, null);
        InputReconstructionResult reconstruction = inputReconstructionAgent.reconstruct(
                runtimeSessionId,
                input,
                preContextPackage,
                preContextPackage == null ? null : preContextPackage.getTaskState(),
                preContextPackage == null ? null : preContextPackage.getRelationalState()
        );
        OrchestrationDecision decision = eventIngressService.ingestUserInput(
                runtimeSessionId,
                input,
                buildOrchestrationSignal(input, reconstruction)
        );
        StructuredContextPackage contextPackage = decision == null ? preContextPackage : decision.getContextPackage();
        RecoveryTrigger recoveryTrigger = resolveRecoveryTrigger(input, decision, contextPackage);
        if (recoveryTrigger.shouldRecover()) {
            contextPackage = recoveryContextAgent.recover(
                    runtimeSessionId,
                    contextPackage,
                    recoveryTrigger.recoveryEvent(),
                    recoveryTrigger.interruptReason()
            );
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_TRIGGERED",
                    "recovery branch entered for interrupted flow",
                    toJsonSafe(Map.of(
                            "event", recoveryTrigger.recoveryEvent(),
                            "reason", recoveryTrigger.interruptReason()
                    ))
            );
        } else {
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "RECOVERY_SKIPPED",
                    "normal chat turn without interrupt/resume event",
                    toJsonSafe(Map.of("input", input))
            );
        }
        runtimeAuditService.persistContextSnapshot(runtimeSessionId, contextPackage);
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "ORCHESTRATION_DECISION",
                "states selected by reconstructed input signal",
                toJsonSafe(buildDecisionStatePayload(decision))
        );
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "INPUT_RECONSTRUCTION",
                "input reconstructed before RAG/MCP routing",
                toJsonSafe(reconstruction)
        );

        List<String> knowledgeSnippets = extractTaskKnowledgeSnippets(contextPackage);
        List<String> preferenceSnippets = extractRelationalPreferenceSnippets(contextPackage);
        List<String> longTermMemorySnippets = extractTaskLongTermSnippets(contextPackage);
        List<String> workingMemorySnippets = extractWorkingMemorySnippets(contextPackage);
        List<String> ragMemorySnippets = new ArrayList<>();
        ContextRerankResult rerankResult = null;
        String mcpDrivenInput = mcpQueryBuilder.build(
                reconstruction,
                decision == null ? null : decision.getTaskState(),
                input
        );
        List<Map<String, Object>> mcpPreRankedCandidates = mcpCandidatePreRank.preRank(
                mcpDrivenInput,
                contextPackage == null ? List.of() : contextPackage.getCapabilityCandidates(),
                reconstruction,
                decision == null ? null : decision.getTaskState(),
                24
        );
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "MCP_PRE_RANK",
                "system-level pre-rank before global semantic rerank",
                toJsonSafe(Map.of(
                        "query", mcpDrivenInput,
                        "candidateCount", mcpPreRankedCandidates.size(),
                        "candidates", mcpPreRankedCandidates
                ))
        );

        try {
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_RETRIEVING, LunaStateConstant.VALUE_RETRIEVING);
            List<ConversationMessage> conversationContext = buildRetrievalConversationContext(contextPackage);
            List<RetrievalRoute> allowedRoutes = resolveAllowedRoutes(decision);
            RetrievalOptions retrievalOptions = resolveRetrievalOptions(input, decision);
            String ragQuery = ragQueryBuilder.build(
                    reconstruction,
                    decision == null ? null : decision.getTaskState(),
                    input
            );
            RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                    .query(ragQuery)
                    .sessionId(runtimeSessionId)
                    .conversationContext(conversationContext)
                    .allowedRoutes(allowedRoutes)
                    .sourceScope(List.of(RetrievalSource.KNOWLEDGE, RetrievalSource.MEMORY, RetrievalSource.PREFERENCE))
                    .options(retrievalOptions)
                    .build();
            RetrievalResponse retrievalResponse = retrievalService.retrieve(retrievalRequest);
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "MULTI_ROUTE_RECALL_TRACE",
                    "raw multi-route retrieval candidates before global rerank",
                    toJsonSafe(Map.of(
                            "ragQuery", ragQuery,
                            "mcpQuery", mcpDrivenInput,
                            "allowedRoutes", allowedRoutes,
                            "knowledgeCandidates", getEvidences(retrievalResponse, RetrievalSource.KNOWLEDGE),
                            "memoryCandidates", getEvidences(retrievalResponse, RetrievalSource.MEMORY),
                            "preferenceCandidates", getEvidences(retrievalResponse, RetrievalSource.PREFERENCE),
                            "mcpPreRankCandidates", mcpPreRankedCandidates
                    ))
            );
            rerankResult = globalContextRerankAgent.rerank(
                    reconstruction,
                    contextPackage,
                    retrievalResponse,
                    mcpPreRankedCandidates,
                    decision == null ? null : decision.getTaskState()
            );
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "BOTTOM_RERANK_DETAIL_TRACE",
                    "bottom rerank detail before global semantic rerank merge",
                    toJsonSafe(Map.of(
                            "knowledgeTopIds", getEvidences(retrievalResponse, RetrievalSource.KNOWLEDGE).stream().limit(20).map(Evidence::getId).toList(),
                            "memoryTopIds", getEvidences(retrievalResponse, RetrievalSource.MEMORY).stream().limit(20).map(Evidence::getId).toList(),
                            "preferenceTopIds", getEvidences(retrievalResponse, RetrievalSource.PREFERENCE).stream().limit(20).map(Evidence::getId).toList(),
                            "mcpPreRankTopNames", mcpPreRankedCandidates.stream().limit(20).map(row -> stringValue(row.get("capability_name"))).toList()
                    ))
            );
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "GLOBAL_CONTEXT_RERANK",
                    "cross-source rerank after retrieval",
                    toJsonSafe(rerankResult)
            );
            rerankTraceLogger.log(runtimeSessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), rerankResult);
            if (rerankResult != null && rerankResult.getSelectedKnowledgeBlocks() != null && !rerankResult.getSelectedKnowledgeBlocks().isEmpty()) {
                knowledgeSnippets = rerankResult.getSelectedKnowledgeBlocks();
            } else {
                knowledgeSnippets = evidenceBlockBuilder.buildKnowledgeBlocks(getEvidences(retrievalResponse, RetrievalSource.KNOWLEDGE));
            }
            ragMemorySnippets.addAll(toMemorySnippets(retrievalResponse));
            if (rerankResult != null && rerankResult.getSelectedMemoryHints() != null) {
                ragMemorySnippets.addAll(rerankResult.getSelectedMemoryHints());
            }
            preferenceSnippets = mergeDistinct(preferenceSnippets, toPreferenceSnippets(retrievalResponse));
            preferenceSnippets = mergeDistinct(
                    preferenceSnippets,
                    mcpResourceHintExtractor.extract(rerankResult == null ? List.of() : rerankResult.getSelectedPromptResources(), 8)
            );
        } catch (Exception e) {
            log.warn("rag retrieve failed: {}", e.getMessage());
        }

        List<String> memorySnippets = new ArrayList<>();
        memorySnippets.addAll(workingMemorySnippets);
        memorySnippets.addAll(extractRuntimeMessageSnippets(contextPackage));
        memorySnippets.addAll(ragMemorySnippets);

        ToolCallingContextHolder.set(ToolCallingContext.builder()
                .chatSessionKey(runtimeSessionId)
                .userInput(input)
                .memorySnippets(memorySnippets)
                .knowledgeSnippets(knowledgeSnippets)
                .preferenceSnippets(preferenceSnippets)
                .longTermMemorySnippets(longTermMemorySnippets)
                .executionCandidates(resolveExecutionCandidates(rerankResult, mcpPreRankedCandidates))
                .mcpResourceHints(mcpResourceHintExtractor.extract(
                        rerankResult == null ? List.of() : rerankResult.getSelectedPromptResources(),
                        8
                ))
                .toolExecutionTraces(new CopyOnWriteArrayList<>())
                .build());

        String toolContext = null;
        List<Resource> executionCandidates = resolveExecutionCandidates(rerankResult, mcpPreRankedCandidates);
        contextSnapshotStore.savePreToolDecisionSnapshot(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                input,
                mcpDrivenInput,
                toExecutionCandidateMaps(executionCandidates),
                Map.of(
                        "rerankedToolCandidateCount", rerankResult == null || rerankResult.getSelectedToolCandidates() == null ? 0 : rerankResult.getSelectedToolCandidates().size(),
                        "rerankedPromptResourceCount", rerankResult == null || rerankResult.getSelectedPromptResources() == null ? 0 : rerankResult.getSelectedPromptResources().size()
                )
        );
        long toolStartAt = System.currentTimeMillis();
        String toolStatus = "SUCCESS";
        String toolError = null;
        try {
            toolContext = agentService.processToolCalling(
                    runtimeSessionId,
                    mcpDrivenInput,
                    decision == null ? null : decision.getTaskState(),
                    decision == null ? null : decision.getRelationalState(),
                    executionCandidates
            );
        } catch (Exception ex) {
            toolStatus = "FAILED";
            toolError = ex.getMessage();
            throw ex;
        } finally {
            List<Map<String, Object>> toolExecutionTraces = ToolCallingContextHolder.snapshotToolExecutionTraces();
            ToolCallingContextHolder.clear();
            persistToolExecutionTraces(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    input,
                    toolContext,
                    toolStatus,
                    toolError,
                    System.currentTimeMillis() - toolStartAt,
                    toolExecutionTraces
            );
            eventIngressService.ingestToolResult(runtimeSessionId, Map.of(
                    "status", toolStatus.toLowerCase(),
                    "toolContext", toolContext == null ? "" : toolContext,
                    "error", toolError == null ? "" : toolError
            ));
        }

        ToolSemanticResult toolSemanticResult = toolSemanticAgent.translate(
                toolContext,
                decision == null ? null : decision.getTaskState(),
                reconstruction == null ? "" : reconstruction.getExplicitTaskGoal()
        );
        ToolSemanticResultValidator.ValidationResult semanticValidation = toolSemanticResultValidator.validate(toolSemanticResult);
        if (!semanticValidation.valid()) {
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "TOOL_SEMANTIC_VALIDATION",
                    "semantic channel validation failed",
                    toJsonSafe(Map.of("issues", semanticValidation.issues()))
            );
        } else {
            runtimeAuditService.persistDecisionRecord(
                    runtimeSessionId,
                    contextPlanId(contextPackage),
                    contextNodeId(contextPackage),
                    "TOOL_SEMANTIC_VALIDATION",
                    "semantic channel validation passed",
                    "{}"
            );
        }
        toolSemanticResult = semanticValidation.normalized() == null ? toolSemanticResult : semanticValidation.normalized();
        runtimeAuditService.persistDecisionRecord(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                "TOOL_SEMANTIC_TRANSLATION",
                "tool result translated to semantic channel",
                toJsonSafe(toolSemanticResult)
        );
        toolSemanticTraceLogger.log(runtimeSessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), toolSemanticResult);

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

        if (isAsyncPending(mergedToolContext)) {
            String pendingReply = buildPendingReply(mergedToolContext);
            cachePendingToolCall(runtimeSessionId, mergedToolContext);
            memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, pendingReply, contextPackage);
            statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
            return ResponseEntity.ok(tryParseJsonNode(pendingReply));
        }

        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_THINKING, LunaStateConstant.VALUE_THINKING_ORGANIZE);
        AssembledContext assembledContext = contextAssembler.assemble(
                contextPackage,
                reconstruction,
                rerankResult,
                toolSemanticResult,
                input,
                memorySnippets,
                knowledgeSnippets,
                preferenceSnippets,
                longTermMemorySnippets,
                mergedToolContext
        );
        contextTraceLogger.log(runtimeSessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), assembledContext);
        contextSnapshotStore.saveFinalSnapshot(
                runtimeSessionId,
                contextPlanId(contextPackage),
                contextNodeId(contextPackage),
                assembledContext,
                assembledContext == null ? "" : assembledContext.getPrompt(),
                assembledContext == null ? Map.of() : assembledContext.getSectionTokenCounts(),
                assembledContext == null ? Map.of() : assembledContext.getSectionTokenRatios()
        );
        String finalPrompt = assembledContext == null || assembledContext.getPrompt() == null || assembledContext.getPrompt().isBlank()
                ? PromptTemplates.SYSTEM_PROMPT + "\n\n" + PromptTemplates.RUNTIME_PROMPT.formatted(input == null ? "" : input)
                : assembledContext.getPrompt();
        SendToLuna result = getSendToLuna(finalPrompt, input, contextPackage);
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());
        memoryWritePipelineService.writeAfterTurn(runtimeSessionId, input, result.replyText(), contextPackage);
        SummaryResult summaryResult = summaryAgent.summarize(input, result.replyText(), contextPackage);
        summaryTraceLogger.log(runtimeSessionId, contextPlanId(contextPackage), contextNodeId(contextPackage), summaryResult);
        writeStateStores(runtimeSessionId, decision, contextPackage, reconstruction, rerankResult, toolSemanticResult, summaryResult);
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.STARTUP, type = LogType.SYSTEM_EVENT, content = "startup")
    public ResponseEntity<Object> startup() {
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_STARTING, LunaStateConstant.VALUE_STARTING);
        LocalDateTime today = LocalDateTime.now();
        String keyPrefix = SESSION_KEY_FORMATTER.format(today);

        List<ChatMessage> recent = sessionService.getRecentMessages(keyPrefix, false);
        if (recent == null) {
            recent = Collections.emptyList();
        }
        int index = 1;
        while (index <= 30 && recent.isEmpty()) {
            recent = sessionService.getRecentMessages(SESSION_KEY_FORMATTER.format(today.minusDays(index++)), true);
            if (recent == null) {
                recent = Collections.emptyList();
            }
        }

        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.STARTUP, "startup", LocalTime.now()));
        List<String> memorySnippets = recent.stream()
                .map(m -> m.getRole().name() + ": " + m.getContent() + ": " + m.getTime())
                .toList();
        String prompt = promptAssembler.assembleStartupPrompt(memorySnippets);
        SendToLuna result = getSendToLuna(prompt, "startup", null);
        LunaLogAspect.LOG_RESPONSE_OVERRIDE.set(result.raw());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.LUNA, result.replyText(), LocalTime.now()));
        statusPublisher.publish(LunaStatusPublisher.DEFAULT_CLIENT_ID, LunaStateConstant.STATUS_IDLE, LunaStateConstant.VALUE_IDLE);
        return ResponseEntity.ok(tryParseJsonNode(result.valid()));
    }

    @Override
    @LunaLogRecord(module = LogModuleConstant.SYSTEM, action = LogActionConstant.SHUTDOWN, type = LogType.SYSTEM_EVENT, content = "shutdown")
    public void shutdown() {
        String keyPrefix = SESSION_KEY_FORMATTER.format(LocalDateTime.now());
        sessionService.appendMessage(keyPrefix, new ChatMessage(ChatMessage.Role.SHUTDOWN, "shutdown", LocalTime.now()));
    }

    @Override
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
    public List<String> getHistory(String yearMonthDay) {
        List<ChatMessage> chats = sessionService.getRecentMessages(yearMonthDay, true);
        if (chats == null) {
            return Collections.emptyList();
        }
        return chats.stream().map(m -> m.getRole().name() + ":" + m.getContent() + ":" + m.getTime()).toList();
    }

    private SendToLuna getSendToLuna(String prompt, String originalUserInput, StructuredContextPackage contextPackage) {
        String executionModelName = resolveExecutionModelName(contextPackage);
        LlmRequest request = LlmRequest.builder()
                .modelType(ModelType.OPENAI_COMPATIBLE)
                .modelName(executionModelName)
                .messages(List.of(LlmMessage.user(prompt)))
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
            String fallbackKey = RedisKeyConstant.GENERATE_FALLBACK_KEY;
            try {
                stringRedisTemplate.opsForValue().set(fallbackKey, "1");
                String repairPrompt = PromptTemplates.REPAIR_PROMPT.formatted(originalUserInput == null ? valid : originalUserInput);
                LlmRequest repairReq = LlmRequest.builder()
                        .modelType(ModelType.OPENAI_COMPATIBLE)
                        .modelName(executionModelName)
                        .messages(List.of(LlmMessage.user(repairPrompt)))
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
            } finally {
                stringRedisTemplate.delete(fallbackKey);
            }
            String fallback = createFallbackJson();
            return new SendToLuna(fallback, removeThoughtFromJson(fallback), extractReplyFromJsonSafe(fallback));
        }

        String raw = node.toString();
        return new SendToLuna(raw, removeThoughtFromJson(raw), node.get(ModelHintConstant.REPLY).asText());
    }

    private String resolveExecutionModelName(StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return geminiProperty.getBig().getModelName();
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        if ((taskState == TaskRuntimeState.PLANNING || taskState == TaskRuntimeState.REPLANNING || taskState == TaskRuntimeState.EXECUTING)
                && geminiProperty.getCode() != null && geminiProperty.getCode().getModelName() != null) {
            return geminiProperty.getCode().getModelName();
        }
        if ((relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == RelationalRuntimeState.REPAIRING)
                && geminiProperty.getChat() != null && geminiProperty.getChat().getModelName() != null) {
            return geminiProperty.getChat().getModelName();
        }
        if (geminiProperty.getBig() != null && geminiProperty.getBig().getModelName() != null) {
            return geminiProperty.getBig().getModelName();
        }
        return geminiProperty.getFlash().getModelName();
    }

    private Long contextPlanId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getRuntime() == null) {
                return null;
            }
            Object session = contextPackage.getRuntime().get("session");
            if (session instanceof Map<?, ?> row) {
                return toLong(row.get("current_plan_id"));
            }
            return null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long contextNodeId(StructuredContextPackage contextPackage) {
        try {
            if (contextPackage == null || contextPackage.getTaskContext() == null) {
                return null;
            }
            Object working = contextPackage.getTaskContext().get("working_memory");
            if (working instanceof Map<?, ?> row) {
                return toLong(row.get("active_node_id"));
            }
            return null;
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

    private boolean isValidReplyNode(JsonNode node) {
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) && node.get(ModelHintConstant.REPLY).isTextual();
    }

    private String createFallbackJson() {
        return "{\"thought\":\"fallback\",\"emotion\":\"Solemn\",\"reply\":\"please try again\"}";
    }

    private String extractReplyFromJsonSafe(String json) {
        JsonNode node = tryParseJsonNode(json);
        return node != null && node.hasNonNull(ModelHintConstant.REPLY) ? node.get(ModelHintConstant.REPLY).asText() : "";
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

    private void persistToolExecutionTraces(String sessionId,
                                            Long planId,
                                            Long nodeId,
                                            String userInput,
                                            String toolContext,
                                            String chainStatus,
                                            String chainError,
                                            long chainLatencyMs,
                                            List<Map<String, Object>> traces) {
        List<Map<String, Object>> safeTraces = traces == null ? List.of() : traces;
        if (safeTraces.isEmpty()) {
            runtimeAuditService.persistToolExecutionTrace(
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
            return;
        }

        int sequence = 1;
        for (Map<String, Object> trace : safeTraces) {
            Map<String, Object> normalizedInput = new LinkedHashMap<>();
            normalizedInput.put("sequence", sequence);
            normalizedInput.put("source_type", stringValue(trace.get("source_type")));
            normalizedInput.put("payload", trace.getOrDefault("normalized_input", Map.of()));

            Map<String, Object> normalizedOutput = new LinkedHashMap<>();
            normalizedOutput.put("sequence", sequence);
            normalizedOutput.put("source_type", stringValue(trace.get("source_type")));
            normalizedOutput.put("payload", trace.getOrDefault("normalized_output", Map.of()));

            runtimeAuditService.persistToolExecutionTrace(
                    sessionId,
                    planId,
                    nodeId,
                    normalizeToolName(trace.get("tool_name"), sequence),
                    normalizeCallStatus(trace.get("call_status")),
                    toJsonSafe(normalizedInput),
                    toJsonSafe(normalizedOutput),
                    stringValue(trace.get("error_message")),
                    normalizeLatency(trace.get("latency_ms"))
            );
            sequence++;
        }

        runtimeAuditService.persistToolExecutionTrace(
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

    private Map<String, Object> buildDecisionStatePayload(OrchestrationDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskState", decision == null || decision.getTaskState() == null ? "" : decision.getTaskState().name());
        payload.put("relationalState", decision == null || decision.getRelationalState() == null ? "" : decision.getRelationalState().name());
        return payload;
    }

    private RecoveryTrigger resolveRecoveryTrigger(String input,
                                                   OrchestrationDecision decision,
                                                   StructuredContextPackage contextPackage) {
        String normalizedInput = stringValue(input).trim().toLowerCase();
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
            String previousEvent = stringValue(contextPackage.getRecoveryState().getRecoveryEvent());
            String previousReason = stringValue(contextPackage.getRecoveryState().getInterruptReason());
            if (!previousEvent.isBlank() && containsAny(previousReason.toLowerCase(), "approval", "tool", "interrupt", "timeout", "failed")) {
                return new RecoveryTrigger(true, previousEvent, previousReason);
            }
        }
        return new RecoveryTrigger(false, "", "");
    }

    private String buildOrchestrationSignal(String rawInput, InputReconstructionResult reconstruction) {
        if (reconstruction == null) {
            return rawInput == null ? "" : rawInput;
        }
        StringBuilder signal = new StringBuilder();
        signal.append("intent=").append(nullSafe(reconstruction.getNormalizedUserIntent()));
        signal.append(";goal=").append(nullSafe(reconstruction.getExplicitTaskGoal()));
        signal.append(";timeScope=").append(nullSafe(reconstruction.getTimeScope()));
        signal.append(";constraints=").append(reconstruction.getBusinessConstraints() == null ? List.of() : reconstruction.getBusinessConstraints());
        signal.append(";missingSlots=").append(reconstruction.getMissingSlots() == null ? List.of() : reconstruction.getMissingSlots());
        return signal.toString();
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
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

    private void writeStateStores(String sessionId,
                                  OrchestrationDecision decision,
                                  StructuredContextPackage contextPackage,
                                  InputReconstructionResult reconstruction,
                                  ContextRerankResult rerankResult,
                                  ToolSemanticResult toolSemanticResult,
                                  SummaryResult summaryResult) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        TaskState previousTaskState = contextPackage == null ? null : contextPackage.getTaskStateEntity();
        RetrievalState previousRetrievalState = contextPackage == null ? null : contextPackage.getRetrievalState();
        ToolState previousToolState = contextPackage == null ? null : contextPackage.getToolState();
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
                        reconstruction == null ? List.of() : mergeDistinct(
                        reconstruction.getReformulatedQueryForRag() == null ? List.of() : List.of(reconstruction.getReformulatedQueryForRag()),
                        reconstruction.getReformulatedQueryForMcp() == null ? List.of() : List.of(reconstruction.getReformulatedQueryForMcp())
                )))
                .retrievalPlan(Map.of(
                        "allowedRoutes", resolveAllowedRoutes(decision),
                        "maxLatencyMs", resolveRetrievalOptions("", decision).getMaxLatencyMs()
                ))
                .selectedEvidenceRefs(rerankResult == null || rerankResult.getSelectedKnowledgeBlocks() == null ? List.of() : rerankResult.getSelectedKnowledgeBlocks())
                .rerankSummary(rerankResult == null ? "" : toJsonSafe(rerankResult.getRationaleByNode()))
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
                .activeKnowledgeRefs(rerankResult == null || rerankResult.getSelectedKnowledgeBlocks() == null ? List.of() : rerankResult.getSelectedKnowledgeBlocks())
                .activeMemoryRefs(rerankResult == null || rerankResult.getSelectedMemoryHints() == null ? List.of() : rerankResult.getSelectedMemoryHints())
                .activeToolEvidenceRefs(List.of("tool_execution_trace:latest"))
                .activeMcpPromptRefs(rerankResult == null || rerankResult.getSelectedPromptResources() == null ? List.of() : rerankResult.getSelectedPromptResources().stream().map(this::toJsonSafe).toList())
                .activeMcpResourceRefs(rerankResult == null || rerankResult.getSelectedToolCandidates() == null ? List.of() : rerankResult.getSelectedToolCandidates().stream().map(this::toJsonSafe).toList())
                .latestContextSnapshotId(sessionId + ":" + System.currentTimeMillis())
                .build();
        contextStateStore.save(sessionId, contextState);
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

    private List<String> toKnowledgeSnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.KNOWLEDGE).stream()
                .map(evidence -> "title: " + nullSafe(evidence.getTitle()) + "\ncontent: " + nullSafe(evidence.getContent()))
                .toList();
    }

    private List<String> toMemorySnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.MEMORY).stream()
                .map(evidence -> "memory: " + nullSafe(evidence.getContent()))
                .toList();
    }

    private List<String> toPreferenceSnippets(RetrievalResponse response) {
        return getEvidences(response, RetrievalSource.PREFERENCE).stream()
                .map(evidence -> "preference: " + nullSafe(evidence.getContent()))
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

    private List<Evidence> getEvidences(RetrievalResponse response, RetrievalSource source) {
        if (response == null || response.getEvidences() == null) {
            return Collections.emptyList();
        }
        return response.getEvidences().getOrDefault(source, Collections.emptyList());
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

    private record SendToLuna(String raw, String valid, String replyText) {
    }

    private record RecoveryTrigger(boolean shouldRecover, String recoveryEvent, String interruptReason) {
    }
}
