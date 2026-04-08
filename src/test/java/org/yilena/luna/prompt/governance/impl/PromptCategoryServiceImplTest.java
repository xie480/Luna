package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.mapper.PromptCategoryMapper;

class PromptCategoryServiceImplTest {

    @Test
    void executionFallbackShouldContainNewExecutionCategories() {
        PromptCategoryMapper mapper = Mockito.mock(PromptCategoryMapper.class);
        Mockito.when(mapper.selectOne(Mockito.any())).thenThrow(new RuntimeException("db unavailable"));
        PromptCategoryServiceImpl service = new PromptCategoryServiceImpl(mapper);

        Assertions.assertTrue(service.isExecutionCategory("memory-hint"));
        Assertions.assertTrue(service.isExecutionCategory("rag-hint"));
        Assertions.assertTrue(service.isExecutionCategory("format"));
    }
}
