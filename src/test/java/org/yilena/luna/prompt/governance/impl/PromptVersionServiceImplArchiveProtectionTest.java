package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.entity.PromptItemVersionEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;

class PromptVersionServiceImplArchiveProtectionTest {

    @Test
    void archiveVersionShouldRejectExecutionPromptCurrentVersion() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptVersionServiceImpl service = new PromptVersionServiceImpl(itemMapper, versionMapper, categoryService);

        Mockito.when(versionMapper.selectById(20L)).thenReturn(PromptItemVersionEntity.builder()
                .id(20L)
                .promptItemId(10L)
                .build());
        Mockito.when(itemMapper.selectById(10L)).thenReturn(PromptItemEntity.builder()
                .id(10L)
                .category("repair")
                .hasTemplateVariables(true)
                .currentVersionId(20L)
                .build());

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class, () -> service.archiveVersion(20L));
        Assertions.assertTrue(ex.getMessage().contains("execution prompt current version cannot be archived"));
        Mockito.verify(versionMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
        Mockito.verify(itemMapper, Mockito.never()).update(Mockito.isNull(), Mockito.any());
    }

    @Test
    void archiveVersionShouldAllowContentPromptCurrentVersion() {
        PromptItemMapper itemMapper = Mockito.mock(PromptItemMapper.class);
        PromptItemVersionMapper versionMapper = Mockito.mock(PromptItemVersionMapper.class);
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        PromptVersionServiceImpl service = new PromptVersionServiceImpl(itemMapper, versionMapper, categoryService);

        Mockito.when(versionMapper.selectById(21L)).thenReturn(PromptItemVersionEntity.builder()
                .id(21L)
                .promptItemId(11L)
                .build());
        Mockito.when(itemMapper.selectById(11L)).thenReturn(PromptItemEntity.builder()
                .id(11L)
                .category("persona")
                .hasTemplateVariables(false)
                .currentVersionId(21L)
                .build());
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        service.archiveVersion(21L);

        Mockito.verify(versionMapper).update(Mockito.isNull(), Mockito.any());
        Mockito.verify(itemMapper).update(Mockito.isNull(), Mockito.any());
    }
}
