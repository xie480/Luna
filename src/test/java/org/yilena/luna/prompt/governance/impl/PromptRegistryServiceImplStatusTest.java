package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.entity.PromptItemEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;

import java.lang.reflect.Method;

class PromptRegistryServiceImplStatusTest {

    @Test
    void itemActiveJudgeShouldOnlyTreatEnabledOrBlankAsActive() throws Exception {
        PromptRegistryServiceImpl service = new PromptRegistryServiceImpl(
                Mockito.mock(PromptItemMapper.class),
                Mockito.mock(PromptItemVersionMapper.class),
                Mockito.mock(PromptCategoryService.class)
        );

        Method isItemActive = PromptRegistryServiceImpl.class.getDeclaredMethod("isItemActive", PromptItemEntity.class);
        isItemActive.setAccessible(true);

        PromptItemEntity enabled = PromptItemEntity.builder().enabled(true).status("enabled").build();
        PromptItemEntity blank = PromptItemEntity.builder().enabled(true).status("").build();
        PromptItemEntity active = PromptItemEntity.builder().enabled(true).status("active").build();
        PromptItemEntity disabled = PromptItemEntity.builder().enabled(true).status("disabled").build();

        Assertions.assertEquals(true, isItemActive.invoke(service, enabled));
        Assertions.assertEquals(true, isItemActive.invoke(service, blank));
        Assertions.assertEquals(false, isItemActive.invoke(service, active));
        Assertions.assertEquals(false, isItemActive.invoke(service, disabled));
    }
}
