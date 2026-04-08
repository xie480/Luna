package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;

import java.lang.reflect.Method;

class PromptMutationServiceImplStatusTest {

    @Test
    void itemStatusParsingShouldOnlyAcceptEnabledDisabled() throws Exception {
        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                Mockito.mock(PromptItemMapper.class),
                Mockito.mock(PromptItemVersionMapper.class),
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                Mockito.mock(PromptCategoryService.class)
        );

        Method resolveItemEnabled = PromptMutationServiceImpl.class
                .getDeclaredMethod("resolveItemEnabled", Boolean.class, String.class, boolean.class);
        resolveItemEnabled.setAccessible(true);
        Method resolveItemEnabledForUpdate = PromptMutationServiceImpl.class
                .getDeclaredMethod("resolveItemEnabledForUpdate", Boolean.class, String.class);
        resolveItemEnabledForUpdate.setAccessible(true);
        Method normalizeItemStatus = PromptMutationServiceImpl.class
                .getDeclaredMethod("normalizeItemStatus", String.class, boolean.class);
        normalizeItemStatus.setAccessible(true);

        Assertions.assertEquals(false, resolveItemEnabled.invoke(service, null, "active", false));
        Assertions.assertEquals(true, resolveItemEnabled.invoke(service, null, "enabled", false));
        Assertions.assertEquals(true, resolveItemEnabled.invoke(service, null, "inactive", true));

        Assertions.assertNull(resolveItemEnabledForUpdate.invoke(service, null, "active"));
        Assertions.assertEquals(true, resolveItemEnabledForUpdate.invoke(service, null, "enabled"));
        Assertions.assertEquals(false, resolveItemEnabledForUpdate.invoke(service, null, "disabled"));

        Assertions.assertEquals("disabled", normalizeItemStatus.invoke(service, "active", false));
        Assertions.assertEquals("enabled", normalizeItemStatus.invoke(service, "enabled", false));
        Assertions.assertEquals("disabled", normalizeItemStatus.invoke(service, "disabled", true));
    }
}
