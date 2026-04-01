package org.yilena.luna.rag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPropertiesTest {

    @Test
    void shouldUseReadableChineseDefaultsWhenNoYamlOverride() {
        RagProperties properties = new RagProperties();
        properties.sanitizeConfiguredKeywords();

        assertTrue(properties.getPreciseKeywords().contains("有没有"));
        assertTrue(properties.getAnalysisKeywords().contains("分析"));
        assertTrue(properties.getRewriteKeywords().contains("结合"));
        assertEquals("请围绕问题进行结构化检索与分析:测试", properties.rewriteWithTemplate("analysis_reasoning", "测试"));
    }

    @Test
    void shouldUseAgenticBeforeNativeInDefaultPriority() {
        RagProperties properties = new RagProperties();

        assertEquals(
                java.util.List.of(
                        RagProperties.RetrievalRouteRule.SEARCH,
                        RagProperties.RetrievalRouteRule.AGENTIC,
                        RagProperties.RetrievalRouteRule.NATIVE,
                        RagProperties.RetrievalRouteRule.MODULAR
                ),
                properties.getRoutePriority()
        );
    }
}
