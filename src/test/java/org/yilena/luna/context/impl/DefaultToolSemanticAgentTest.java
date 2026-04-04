package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.utils.LlmClientUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolSemanticAgentTest {

    @Test
    void shouldThrowWhenModelTranslationFailsAfterRetries() {
        LlmClientUtil llmClientUtil = mock(LlmClientUtil.class);
        when(llmClientUtil.generate(any())).thenReturn(LlmResponse.builder().content("").build());

        DefaultToolSemanticAgent agent = new DefaultToolSemanticAgent(
                new ObjectMapper(),
                llmClientUtil,
                geminiProperty("gpt-small")
        );

        assertThrows(IllegalStateException.class, () -> agent.translate(
                "calendar.create",
                "create a calendar task",
                "{\"status\":\"ok\"}",
                TaskRuntimeState.EXECUTING,
                "create meeting"
        ));
        verify(llmClientUtil, times(3)).generate(any());
    }

    @Test
    void shouldReturnModelResultWithoutHeuristicFallback() {
        LlmClientUtil llmClientUtil = mock(LlmClientUtil.class);
        when(llmClientUtil.generate(any())).thenReturn(LlmResponse.builder().content("""
                {
                  "toolName":"calendar.create",
                  "toolDescription":"create a calendar task",
                  "toolStatus":"SUCCESS",
                  "keyFacts":["event created"],
                  "businessImpact":"schedule confirmed",
                  "unresolvedIssues":[],
                  "nextStepHint":"send confirmation",
                  "confidence":0.92
                }
                """).build());

        DefaultToolSemanticAgent agent = new DefaultToolSemanticAgent(
                new ObjectMapper(),
                llmClientUtil,
                geminiProperty("gpt-small")
        );

        var result = agent.translate(
                "calendar.create",
                "create a calendar task",
                "{\"status\":\"ok\"}",
                TaskRuntimeState.EXECUTING,
                "create meeting"
        );

        assertEquals("SUCCESS", result.getToolStatus());
        assertEquals("send confirmation", result.getNextStepHint());
        assertEquals("schedule confirmed", result.getBusinessImpact());
        assertEquals(1, result.getKeyFacts().size());
    }

    private GeminiProperty geminiProperty(String modelName) {
        GeminiProperty property = new GeminiProperty();
        GeminiProperty.ModelConfig flash = new GeminiProperty.ModelConfig();
        flash.setModelName(modelName);
        property.setFlash(flash);
        return property;
    }
}
