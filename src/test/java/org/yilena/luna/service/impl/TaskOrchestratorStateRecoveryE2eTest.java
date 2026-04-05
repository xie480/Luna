package org.yilena.luna.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import org.yilena.luna.context.SummaryStateSnapshotValidator;
import org.yilena.luna.context.SummaryAgent;
import org.yilena.luna.context.SummaryTraceLogger;
import org.yilena.luna.context.ContextAssembler;
import org.yilena.luna.context.ContextTraceLogger;
import org.yilena.luna.context.ToolSemanticResultValidator;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.ContextCompilerService;
import org.yilena.luna.memory.EventIngressService;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.rag.api.RetrievalService;
import org.yilena.luna.rag.models.Evidence;
import org.yilena.luna.rag.models.RetrievalResponse;
import org.yilena.luna.rag.models.RetrievalRoute;
import org.yilena.luna.rag.models.RetrievalSource;
import org.yilena.luna.router.CapabilityPolicyRouterService;
import org.yilena.luna.router.ToolRouter;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.service.SessionService;
import org.yilena.luna.service.model.NodeWorksetResult;
import org.yilena.luna.service.model.SummaryOrchestrationResult;
import org.yilena.luna.service.model.TaskOrchestrationResult;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.store.ContextStateStore;
import org.yilena.luna.state.store.RecoveryStateStore;
import org.yilena.luna.state.store.TaskStateStore;
import org.yilena.luna.state.store.RetrievalStateStore;
import org.yilena.luna.state.store.ToolStateStore;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOrchestratorStateRecoveryE2eTest {

    @Test
    void shouldConsumeRecoveryRefreshPlanAndClearRecoveryStateInNodeWorkset() {
        TestFixture fixture = new TestFixture();
        when(fixture.capabilityPolicyRouterService.routeForContext(anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(fixture.mcpCandidatePreRank.preRank(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());
        when(fixture.retrievalService.retrieve(any())).thenReturn(
                RetrievalResponse.builder().route(RetrievalRoute.SEARCH).rewrittenQuery("q").evidences(Map.of()).build()
        );
        when(fixture.globalContextRerankAgent.rerank(any(), any(), any(), anyList(), any())).thenReturn(null);
        when(fixture.toolRouter.materializeCandidates(anyList(), anyInt())).thenReturn(List.of());
        when(fixture.mcpResourceHintExtractor.extract(anyList(), anyInt())).thenReturn(List.of());

        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.EXECUTING)
                .retrievalState(RetrievalState.builder()
                        .reconstructedIntent("intent")
                        .activeQueries(List.of("q1"))
                        .retrievalPlan(Map.of(
                                "need_rag_refresh", true,
                                "need_mcp_refresh", true,
                                "need_reassembly", true,
                                "invalidated_evidence_refs", List.of("knowledge:1"),
                                "invalidated_capability_names", List.of("search_knowledge"),
                                "invalidation_reasons_by_ref", Map.of("knowledge:1", "stale")
                        ))
                        .selectedEvidenceRefs(List.of())
                        .rerankSummary("")
                        .build())
                .build();
        InputReconstructionResult reconstruction = InputReconstructionResult.builder()
                .reformulatedQueryForMcp("mcp-query")
                .reformulatedQueryForRag("rag-query")
                .explicitTaskGoal("goal")
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.EXECUTING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .contextPackage(contextPackage)
                .build();

        NodeWorksetResult result = fixture.service.orchestrateNodeWorkset(
                "s-1",
                "继续执行",
                decision,
                contextPackage,
                reconstruction
        );

        assertNotNull(result);
        assertEquals(List.of("knowledge:1"), result.getInvalidatedEvidenceRefs());
        assertEquals(List.of("search_knowledge"), result.getInvalidatedCapabilityNames());
        verify(fixture.recoveryStateStore).clear("s-1");
    }

    @Test
    void shouldPersistSummaryStateAndHistoryInOrchestrator() {
        TestFixture fixture = new TestFixture();
        StructuredContextPackage context = StructuredContextPackage.builder()
                .sessionId("s-1")
                .build();
        when(fixture.contextCompilerService.compile(eq("s-1"), anyString(), any(), any())).thenReturn(context);
        when(fixture.summaryAgent.summarize(anyString(), anyString(), any(), anyList(), anyList(), any()))
                .thenReturn(SummaryResult.builder()
                        .narrativeSummary("narrative")
                        .stateSnapshot(Map.of("nextStep", "continue"))
                        .build());
        when(fixture.contextStateStore.load("s-1")).thenReturn(ContextState.builder()
                .latestNarrativeSummary("")
                .latestStateSnapshot(Map.of())
                .activeKnowledgeRefs(List.of("k1"))
                .activeMemoryRefs(List.of("m1"))
                .activeToolEvidenceRefs(List.of("t1"))
                .activeMcpPromptRefs(List.of("p1"))
                .activeMcpResourceRefs(List.of("r1"))
                .latestContextSnapshotId("snap-1")
                .build());

        SummaryOrchestrationResult result = fixture.service.orchestrateSummary(
                "s-1",
                "输入",
                "回复",
                null,
                List.of(),
                List.of(),
                null,
                true,
                "TEST"
        );

        assertNotNull(result);
        assertEquals("narrative", result.getSummaryResult().getNarrativeSummary());
        ArgumentCaptor<ContextState> stateCaptor = ArgumentCaptor.forClass(ContextState.class);
        verify(fixture.contextStateStore).save(eq("s-1"), stateCaptor.capture());
        assertTrue(stateCaptor.getValue().getLatestStateSnapshot().containsKey("nextStep"));
        verify(fixture.sessionService, times(1)).replaceHistoryWithSummary(eq("s-1"), eq("narrative"), anyString());
    }

    @Test
    void shouldRunImmediateRecoveryRefreshInSameRound() {
        TestFixture fixture = new TestFixture();
        when(fixture.contextCompilerService.compile(eq("s-1"), anyString(), any(), any())).thenReturn(StructuredContextPackage.builder().sessionId("s-1").build());
        InputReconstructionResult reconstruction = InputReconstructionResult.builder()
                .normalizedUserIntent("intent")
                .explicitTaskGoal("goal")
                .reformulatedQueryForRag("rag-q")
                .reformulatedQueryForMcp("mcp-q")
                .build();
        when(fixture.inputReconstructionAgent.reconstruct(eq("s-1"), anyString(), any(), any(), any())).thenReturn(reconstruction);
        StructuredContextPackage recoveredContext = StructuredContextPackage.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.WAITING_TOOL)
                .retrievalState(RetrievalState.builder()
                        .reconstructedIntent("intent")
                        .activeQueries(List.of("q1"))
                        .retrievalPlan(Map.of(
                                "refresh_rag_now", true,
                                "refresh_mcp_now", true,
                                "reassemble_now", true
                        ))
                        .selectedEvidenceRefs(List.of())
                        .rerankSummary("")
                        .build())
                .contextState(ContextState.builder()
                        .latestNarrativeSummary("")
                        .latestStateSnapshot(Map.of())
                        .activeKnowledgeRefs(List.of())
                        .activeMemoryRefs(List.of())
                        .activeToolEvidenceRefs(List.of())
                        .activeMcpPromptRefs(List.of())
                        .activeMcpResourceRefs(List.of())
                        .latestContextSnapshotId("snap-1")
                        .build())
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.WAITING_TOOL)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .contextPackage(recoveredContext)
                .build();
        when(fixture.eventIngressService.ingestUserInput(eq("s-1"), anyString(), any())).thenReturn(decision);
        when(fixture.recoveryContextAgent.recover(eq("s-1"), any(), anyString(), anyString())).thenReturn(recoveredContext);
        when(fixture.capabilityPolicyRouterService.routeForContext(anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(fixture.mcpCandidatePreRank.preRank(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of());
        when(fixture.retrievalService.retrieve(any())).thenReturn(
                RetrievalResponse.builder().route(RetrievalRoute.SEARCH).rewrittenQuery("q").evidences(Map.of()).build()
        );
        when(fixture.globalContextRerankAgent.rerank(any(), any(), any(), anyList(), any())).thenReturn(null);
        when(fixture.evidenceBlockBuilder.buildKnowledgeBlocks(anyList())).thenReturn(List.of());
        when(fixture.toolRouter.materializeCandidates(anyList(), anyInt())).thenReturn(List.of());
        when(fixture.mcpResourceHintExtractor.extract(anyList(), anyInt())).thenReturn(List.of());

        TaskOrchestrationResult result = fixture.service.orchestrateUserInput("s-1", "resume execution");
        assertNotNull(result);
        assertNotNull(result.getContextPackage());
        assertNotNull(result.getContextPackage().getRetrievalState());
        assertTrue(Boolean.TRUE.equals(result.getContextPackage().getRetrievalState().getRetrievalPlan().get("immediate_refresh_executed")));
        verify(fixture.retrievalStateStore, times(1)).save(eq("s-1"), any());
        verify(fixture.contextStateStore, times(1)).save(eq("s-1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistBottomRerankTraceBeforeGlobalRerankAndIncludePreRankFields() throws Exception {
        TestFixture fixture = new TestFixture();
        when(fixture.capabilityPolicyRouterService.routeForContext(anyString(), anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(fixture.mcpCandidatePreRank.preRank(anyString(), anyList(), any(), any(), anyInt())).thenReturn(List.of(
                Map.of(
                        "capability_name", "kb_search",
                        "capability_type", "tool",
                        "server_code", "local",
                        "final_score", "0.73",
                        "requires_approval", false,
                        "sensitivity", "LOW"
                )
        ));

        Evidence knowledge = Evidence.builder()
                .id("knowledge:1")
                .source(RetrievalSource.KNOWLEDGE)
                .type("knowledge")
                .title("k1")
                .content("k1-content")
                .score(0.91)
                .build();
        Evidence memory = Evidence.builder()
                .id("memory:1")
                .source(RetrievalSource.MEMORY)
                .type("memory")
                .title("m1")
                .content("m1-content")
                .score(0.62)
                .build();
        Evidence preference = Evidence.builder()
                .id("preference:1")
                .source(RetrievalSource.PREFERENCE)
                .type("preference")
                .title("p1")
                .content("p1-content")
                .score(0.57)
                .build();
        Map<RetrievalSource, List<Evidence>> evidences = new LinkedHashMap<>();
        evidences.put(RetrievalSource.KNOWLEDGE, List.of(knowledge));
        evidences.put(RetrievalSource.MEMORY, List.of(memory));
        evidences.put(RetrievalSource.PREFERENCE, List.of(preference));
        when(fixture.retrievalService.retrieve(any())).thenReturn(
                RetrievalResponse.builder().route(RetrievalRoute.SEARCH).rewrittenQuery("q").evidences(evidences).build()
        );

        when(fixture.globalContextRerankAgent.rerank(any(), any(), any(), anyList(), any())).thenReturn(null);
        when(fixture.evidenceBlockBuilder.buildKnowledgeBlocks(anyList())).thenReturn(List.of());
        when(fixture.toolRouter.materializeCandidates(anyList(), anyInt())).thenReturn(List.of());
        when(fixture.mcpResourceHintExtractor.extract(anyList(), anyInt())).thenReturn(List.of());

        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.EXECUTING)
                .build();
        InputReconstructionResult reconstruction = InputReconstructionResult.builder()
                .reformulatedQueryForMcp("mcp-q")
                .reformulatedQueryForRag("rag-q")
                .explicitTaskGoal("goal")
                .build();
        OrchestrationDecision decision = OrchestrationDecision.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.EXECUTING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .contextPackage(contextPackage)
                .build();

        fixture.service.orchestrateNodeWorkset(
                "s-1",
                "run",
                decision,
                contextPackage,
                reconstruction
        );

        InOrder ordered = inOrder(fixture.runtimeAuditService, fixture.globalContextRerankAgent);
        ordered.verify(fixture.runtimeAuditService).persistDecisionRecord(anyString(), any(), any(), eq("MULTI_ROUTE_RECALL_TRACE"), anyString(), anyString());
        ordered.verify(fixture.runtimeAuditService).persistDecisionRecord(anyString(), any(), any(), eq("RERANK_TRACE_BOTTOM_CHANNELS"), anyString(), anyString());
        ordered.verify(fixture.globalContextRerankAgent).rerank(any(), any(), any(), anyList(), any());

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.runtimeAuditService, times(3))
                .persistDecisionRecord(anyString(), any(), any(), typeCaptor.capture(), anyString(), payloadCaptor.capture());

        String bottomPayload = "";
        for (int i = 0; i < typeCaptor.getAllValues().size(); i++) {
            if ("RERANK_TRACE_BOTTOM_CHANNELS".equals(typeCaptor.getAllValues().get(i))) {
                bottomPayload = payloadCaptor.getAllValues().get(i);
                break;
            }
        }
        assertTrue(bottomPayload != null && !bottomPayload.isBlank());
        Map<String, Object> payload = new ObjectMapper().readValue(bottomPayload, Map.class);
        List<Map<String, Object>> knowledgeRows = (List<Map<String, Object>>) payload.get("knowledgeBottomRerank");
        List<Map<String, Object>> mcpRows = (List<Map<String, Object>>) payload.get("mcpBottomRerank");
        assertNotNull(knowledgeRows);
        assertNotNull(mcpRows);
        assertTrue(!knowledgeRows.isEmpty());
        assertTrue(!mcpRows.isEmpty());
        assertTrue(knowledgeRows.get(0).containsKey("preRankOrder"));
        assertTrue(knowledgeRows.get(0).containsKey("preRankScore"));
        assertTrue(mcpRows.get(0).containsKey("preRankOrder"));
        assertTrue(mcpRows.get(0).containsKey("preRankScore"));
    }

    private static class TestFixture {
        final ContextCompilerService contextCompilerService = mock(ContextCompilerService.class);
        final InputReconstructionAgent inputReconstructionAgent = mock(InputReconstructionAgent.class);
        final EventIngressService eventIngressService = mock(EventIngressService.class);
        final RecoveryContextAgent recoveryContextAgent = mock(RecoveryContextAgent.class);
        final RuntimeAuditService runtimeAuditService = mock(RuntimeAuditService.class);
        final RagQueryBuilder ragQueryBuilder = new RagQueryBuilder();
        final MemoryQueryBuilder memoryQueryBuilder = new MemoryQueryBuilder();
        final McpQueryBuilder mcpQueryBuilder = new McpQueryBuilder();
        final McpCandidatePreRank mcpCandidatePreRank = mock(McpCandidatePreRank.class);
        final McpResourceHintExtractor mcpResourceHintExtractor = mock(McpResourceHintExtractor.class);
        final GlobalContextRerankAgent globalContextRerankAgent = mock(GlobalContextRerankAgent.class);
        final EvidenceBlockBuilder evidenceBlockBuilder = mock(EvidenceBlockBuilder.class);
        final RetrievalService retrievalService = mock(RetrievalService.class);
        final CapabilityPolicyRouterService capabilityPolicyRouterService = mock(CapabilityPolicyRouterService.class);
        final ToolRouter toolRouter = mock(ToolRouter.class);
        final RerankTraceLogger rerankTraceLogger = mock(RerankTraceLogger.class);
        final RecoveryStateStore recoveryStateStore = mock(RecoveryStateStore.class);
        final SummaryAgent summaryAgent = mock(SummaryAgent.class);
        final SummaryStateSnapshotValidator summaryStateSnapshotValidator = mock(SummaryStateSnapshotValidator.class);
        final SummaryTraceLogger summaryTraceLogger = mock(SummaryTraceLogger.class);
        final ContextAssembler contextAssembler = mock(ContextAssembler.class);
        final ContextTraceLogger contextTraceLogger = mock(ContextTraceLogger.class);
        final ContextStateStore contextStateStore = mock(ContextStateStore.class);
        final TaskStateStore taskStateStore = mock(TaskStateStore.class);
        final RetrievalStateStore retrievalStateStore = mock(RetrievalStateStore.class);
        final ToolStateStore toolStateStore = mock(ToolStateStore.class);
        final ToolSemanticResultValidator toolSemanticResultValidator = mock(ToolSemanticResultValidator.class);
        final LlmClientUtil llmClientUtil = mock(LlmClientUtil.class);
        final GeminiProperty geminiProperty = new GeminiProperty();
        final SessionService sessionService = mock(SessionService.class);

        final TaskOrchestratorServiceImpl service = new TaskOrchestratorServiceImpl(
                contextCompilerService,
                inputReconstructionAgent,
                eventIngressService,
                recoveryContextAgent,
                runtimeAuditService,
                ragQueryBuilder,
                memoryQueryBuilder,
                mcpQueryBuilder,
                mcpCandidatePreRank,
                mcpResourceHintExtractor,
                globalContextRerankAgent,
                evidenceBlockBuilder,
                retrievalService,
                capabilityPolicyRouterService,
                toolRouter,
                rerankTraceLogger,
                recoveryStateStore,
                summaryAgent,
                summaryStateSnapshotValidator,
                summaryTraceLogger,
                contextAssembler,
                contextTraceLogger,
                contextStateStore,
                taskStateStore,
                retrievalStateStore,
                toolStateStore,
                toolSemanticResultValidator,
                llmClientUtil,
                geminiProperty,
                sessionService,
                new ObjectMapper()
        );
    }
}
