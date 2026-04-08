package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptCategoryService;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.PromptVersionService;
import org.yilena.luna.prompt.governance.dto.PromptUpsertRequest;
import org.yilena.luna.prompt.governance.entity.PromptCategoryEntity;
import org.yilena.luna.prompt.governance.mapper.PromptItemMapper;
import org.yilena.luna.prompt.governance.mapper.PromptItemVersionMapper;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

class PromptMutationServiceImplCreateValidationTest {

    @Test
    void createShouldRejectTemplatePlaceholderInValue() throws Exception {
        PromptCategoryService categoryService = Mockito.mock(PromptCategoryService.class);
        Mockito.when(categoryService.findByKey("persona"))
                .thenReturn(Optional.of(PromptCategoryEntity.builder()
                        .categoryKey("persona")
                        .isExecutionCategory(false)
                        .build()));
        Mockito.when(categoryService.isExecutionCategory("persona")).thenReturn(false);

        PromptMutationServiceImpl service = new PromptMutationServiceImpl(
                Mockito.mock(PromptItemMapper.class),
                Mockito.mock(PromptItemVersionMapper.class),
                Mockito.mock(PromptVersionService.class),
                Mockito.mock(PromptRegistryService.class),
                categoryService
        );

        PromptUpsertRequest request = new PromptUpsertRequest();
        request.setKey("persona.template_placeholder");
        request.setCategory("persona");
        request.setValue("hello ${user_name}");

        Method method = PromptMutationServiceImpl.class
                .getDeclaredMethod("validateCreateRequest", PromptUpsertRequest.class);
        method.setAccessible(true);

        InvocationTargetException ex = Assertions.assertThrows(InvocationTargetException.class, () -> method.invoke(service, request));
        Assertions.assertNotNull(ex.getCause());
        Assertions.assertTrue(ex.getCause().getMessage().contains("content prompt create value cannot carry template placeholder"));
    }
}
