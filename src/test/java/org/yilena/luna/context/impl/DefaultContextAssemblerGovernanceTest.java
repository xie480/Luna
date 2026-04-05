package org.yilena.luna.context.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.context.SemanticPreservingPruner;
import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

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
                mock(org.yilena.luna.context.ToolSemanticAgent.class)
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
                "敏感原文请不要直驱下游",
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
        assertFalse(semanticQuery.contains("敏感原文请不要直驱下游"));
        assertTrue(assembled.getPrompt() != null && !assembled.getPrompt().isBlank());
    }
}
