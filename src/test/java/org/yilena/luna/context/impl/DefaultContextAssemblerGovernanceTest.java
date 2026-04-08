package org.yilena.luna.context.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.yilena.luna.context.SemanticPreservingPruner;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.prompt.governance.PromptSnapshotBridgeService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultContextAssemblerGovernanceTest {

    @Test
    void shouldNotFallbackToRawUserInputForOnDemandMemoryQuery() {
        SemanticPreservingPruner pruner = new SemanticPreservingPruner();
        TaskMemoryRetriever taskMemoryRetriever = mock(TaskMemoryRetriever.class);
        RelationalMemoryRetriever relationalMemoryRetriever = mock(RelationalMemoryRetriever.class);
        DefaultContextAssembler assembler = new DefaultContextAssembler(
                pruner,
                taskMemoryRetriever,
                relationalMemoryRetriever,
                mock(org.yilena.luna.context.SummaryAgent.class),
                mock(org.yilena.luna.context.ToolSemanticAgent.class),
                mock(org.yilena.luna.context.ContextSnapshotWriter.class)
        );
        when(taskMemoryRetriever.retrieve(anyString(), anyString(), any())).thenReturn(Map.of());

        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-1")
                .taskState(TaskRuntimeState.CONTEXT_BUILDING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .tokenBudgetPlan(Map.of())
                .build();

        AssembledContext assembled = assembler.assemble(
                contextPackage,
                null,
                null,
                null,
                "sensitive raw input should not be used directly",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                ContextNodeTemplatePolicy.defaultPolicy(),
                null,
                "s-1",
                100L,
                200L
        );

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMemoryRetriever).retrieve(eq("s-1"), queryCaptor.capture(), eq(TaskRuntimeState.CONTEXT_BUILDING));
        String semanticQuery = queryCaptor.getValue();
        assertTrue(semanticQuery.contains("goal=goal_unavailable"));
        assertTrue(semanticQuery.contains("query_source=governed_structured_signal"));
        assertFalse(semanticQuery.contains("sensitive raw input should not be used directly"));
        assertTrue(assembled.getPrompt() != null && !assembled.getPrompt().isBlank());
    }

    @Test
    void shouldBuildPromptAssemblyMetaViaSnapshotBridgeService() throws Exception {
        SemanticPreservingPruner pruner = new SemanticPreservingPruner();
        TaskMemoryRetriever taskMemoryRetriever = mock(TaskMemoryRetriever.class);
        RelationalMemoryRetriever relationalMemoryRetriever = mock(RelationalMemoryRetriever.class);
        PromptSnapshotBridgeService bridgeService = mock(PromptSnapshotBridgeService.class);
        when(taskMemoryRetriever.retrieve(anyString(), anyString(), any())).thenReturn(Map.of());
        when(bridgeService.buildSnapshotPayload(Mockito.isNull(), eq("policy-1")))
                .thenReturn(Map.of(
                        "policyId", "policy-1",
                        "assemblerVersion", "assembler.v1",
                        "promptRefs", List.of(),
                        "slotMapping", Map.of()
                ));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
                pruner,
                taskMemoryRetriever,
                relationalMemoryRetriever,
                mock(org.yilena.luna.context.SummaryAgent.class),
                mock(org.yilena.luna.context.ToolSemanticAgent.class),
                mock(org.yilena.luna.context.ContextSnapshotWriter.class)
        );
        Field bridgeField = DefaultContextAssembler.class.getDeclaredField("promptSnapshotBridgeService");
        bridgeField.setAccessible(true);
        bridgeField.set(assembler, bridgeService);

        StructuredContextPackage contextPackage = StructuredContextPackage.builder()
                .sessionId("s-2")
                .taskState(TaskRuntimeState.CONTEXT_BUILDING)
                .relationalState(RelationalRuntimeState.LIGHT_CHAT)
                .tokenBudgetPlan(Map.of())
                .promptPolicy(Map.of("policyId", "policy-1"))
                .build();

        AssembledContext assembled = assembler.assemble(
                contextPackage,
                null,
                null,
                null,
                "hello",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                ContextNodeTemplatePolicy.defaultPolicy(),
                null,
                "s-2",
                10L,
                20L
        );

        verify(bridgeService).buildSnapshotPayload(Mockito.isNull(), eq("policy-1"));
        assertTrue(assembled.getPromptAssemblyMeta() != null);
        assertTrue(assembled.getPromptAssemblyMeta().containsKey("promptRefs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeduplicatePromptRefsAndPreferResolverHit() throws Exception {
        SemanticPreservingPruner pruner = new SemanticPreservingPruner();
        TaskMemoryRetriever taskMemoryRetriever = mock(TaskMemoryRetriever.class);
        RelationalMemoryRetriever relationalMemoryRetriever = mock(RelationalMemoryRetriever.class);
        PromptSnapshotBridgeService bridgeService = mock(PromptSnapshotBridgeService.class);
        when(bridgeService.buildSnapshotPayload(Mockito.isNull(), eq("policy-1")))
                .thenReturn(Map.of(
                        "policyId", "policy-1",
                        "assemblerVersion", "assembler.v1",
                        "promptRefs", List.of(
                                Map.of(
                                        "key", "system.base_v1",
                                        "versionId", 11L,
                                        "runtimeSlot", "instructions.system",
                                        "matchReason", "RESOLVER_HIT",
                                        "value", "resolved"
                                )
                        ),
                        "slotMapping", Map.of()
                ));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
                pruner,
                taskMemoryRetriever,
                relationalMemoryRetriever,
                mock(org.yilena.luna.context.SummaryAgent.class),
                mock(org.yilena.luna.context.ToolSemanticAgent.class),
                mock(org.yilena.luna.context.ContextSnapshotWriter.class)
        );
        Field bridgeField = DefaultContextAssembler.class.getDeclaredField("promptSnapshotBridgeService");
        bridgeField.setAccessible(true);
        bridgeField.set(assembler, bridgeService);

        Method method = DefaultContextAssembler.class.getDeclaredMethod(
                "buildPromptAssemblyMeta",
                org.yilena.luna.prompt.governance.model.PromptResolveResult.class,
                List.class,
                String.class
        );
        method.setAccessible(true);
        Map<String, Object> meta = (Map<String, Object>) method.invoke(
                assembler,
                null,
                List.of(
                        Map.of(
                                "key", "system.base_v1",
                                "versionId", 11L,
                                "runtimeSlot", "instructions.system",
                                "matchReason", "FALLBACK",
                                "value", "fallback"
                        )
                ),
                "policy-1"
        );
        List<Map<String, Object>> refs = (List<Map<String, Object>>) meta.get("promptRefs");
        assertEquals(1, refs.size());
        assertEquals("RESOLVER_HIT", refs.get(0).get("matchReason"));
    }
}
