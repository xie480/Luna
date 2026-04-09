package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.mapper.PromptCategoryMapper;
import org.yilena.luna.prompt.governance.model.PromptCategoryTreeNode;

import java.util.List;

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

    @Test
    void listEnabledTreeShouldGroupByParentCategoryKey() {
        PromptCategoryMapper mapper = Mockito.mock(PromptCategoryMapper.class);
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(
                PromptCategoryEntity.builder()
                        .categoryKey("root")
                        .categoryName("Root")
                        .parentCategoryKey("")
                        .sortOrder(10)
                        .keywordMatchAllowed(true)
                        .isExecutionCategory(false)
                        .enabled(true)
                        .build(),
                PromptCategoryEntity.builder()
                        .categoryKey("child-a")
                        .categoryName("Child A")
                        .parentCategoryKey("root")
                        .sortOrder(9)
                        .keywordMatchAllowed(true)
                        .isExecutionCategory(false)
                        .enabled(true)
                        .build(),
                PromptCategoryEntity.builder()
                        .categoryKey("child-b")
                        .categoryName("Child B")
                        .parentCategoryKey("root")
                        .sortOrder(8)
                        .keywordMatchAllowed(false)
                        .isExecutionCategory(true)
                        .enabled(true)
                        .build()
        ));
        PromptCategoryServiceImpl service = new PromptCategoryServiceImpl(mapper);

        List<PromptCategoryTreeNode> tree = service.listEnabledTree();

        Assertions.assertEquals(1, tree.size());
        PromptCategoryTreeNode root = tree.get(0);
        Assertions.assertEquals("root", root.getCategoryKey());
        Assertions.assertEquals(2, root.getChildren().size());
        Assertions.assertEquals("child-a", root.getChildren().get(0).getCategoryKey());
        Assertions.assertEquals("child-b", root.getChildren().get(1).getCategoryKey());
    }
}
