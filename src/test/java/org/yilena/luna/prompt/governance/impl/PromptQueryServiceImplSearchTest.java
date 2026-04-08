package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yilena.luna.prompt.governance.PromptRegistryService;
import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.model.EditPolicy;
import org.yilena.luna.prompt.governance.model.MatchScope;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;

class PromptQueryServiceImplSearchTest {

    @Test
    void searchShouldReturnDisabledRowsWhenEnabledFalse() {
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptQueryServiceImpl service = new PromptQueryServiceImpl(registryService);
        PromptItemRecord disabledItem = sampleRecord("persona.disabled", false);
        Mockito.when(registryService.listAll(true)).thenReturn(List.of(disabledItem));

        PromptSearchRequest request = new PromptSearchRequest();
        request.setEnabled(false);

        List<PromptItemRecord> rows = service.search(request);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertFalse(rows.get(0).isEnabled());
        Mockito.verify(registryService).listAll(true);
    }

    @Test
    void searchShouldUseActiveSourceWhenEnabledNotProvided() {
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptQueryServiceImpl service = new PromptQueryServiceImpl(registryService);
        PromptItemRecord enabledItem = sampleRecord("persona.enabled", true);
        Mockito.when(registryService.listAll(false)).thenReturn(List.of(enabledItem));

        List<PromptItemRecord> rows = service.search(new PromptSearchRequest());
        Assertions.assertEquals(1, rows.size());
        Assertions.assertTrue(rows.get(0).isEnabled());
        Mockito.verify(registryService).listAll(false);
    }

    @Test
    void searchShouldUseIncludeDisabledSourceWhenIncludeDisabledTrue() {
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptQueryServiceImpl service = new PromptQueryServiceImpl(registryService);
        PromptItemRecord enabledItem = sampleRecord("persona.enabled", true);
        PromptItemRecord disabledItem = sampleRecord("persona.disabled", false);
        Mockito.when(registryService.listAll(true)).thenReturn(List.of(enabledItem, disabledItem));

        PromptSearchRequest request = new PromptSearchRequest();
        request.setIncludeDisabled(true);

        List<PromptItemRecord> rows = service.search(request);
        Assertions.assertEquals(2, rows.size());
        Mockito.verify(registryService).listAll(true);
    }

    @Test
    void searchShouldUseActiveSourceWhenEnabledTrue() {
        PromptRegistryService registryService = Mockito.mock(PromptRegistryService.class);
        PromptQueryServiceImpl service = new PromptQueryServiceImpl(registryService);
        PromptItemRecord enabledItem = sampleRecord("persona.enabled", true);
        Mockito.when(registryService.listAll(false)).thenReturn(List.of(enabledItem));

        PromptSearchRequest request = new PromptSearchRequest();
        request.setEnabled(true);

        List<PromptItemRecord> rows = service.search(request);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertTrue(rows.get(0).isEnabled());
        Mockito.verify(registryService).listAll(false);
    }

    private PromptItemRecord sampleRecord(String key, boolean enabled) {
        return PromptItemRecord.builder()
                .itemId(1L)
                .versionId(1L)
                .key(key)
                .name(key)
                .value("value")
                .category("persona")
                .subCategory("default")
                .description("")
                .runtimeSlot("instructions.persona")
                .hasTemplateVariables(false)
                .templateVariables(List.of())
                .keywordMatchEnabled(false)
                .matchKeywords(List.of())
                .assemblyMode("KEYWORD_ONLY")
                .matchScope(MatchScope.empty())
                .editPolicy(EditPolicy.contentDefault())
                .enabled(enabled)
                .priority(10)
                .status(enabled ? "enabled" : "disabled")
                .version("1.0.0")
                .versionLabel("1.0.0")
                .changeNote("")
                .build();
    }
}
