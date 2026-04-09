package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Test;
import org.yilena.luna.prompt.governance.PromptResolverService;
import org.yilena.luna.prompt.governance.model.PromptResolveContext;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptPreviewServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void previewMatchShouldContainReasonAliasField() {
        PromptResolverService resolverService = mock(PromptResolverService.class);
        when(resolverService.resolve(org.mockito.ArgumentMatchers.any(PromptResolveContext.class)))
                .thenReturn(PromptResolveResult.builder()
                        .policyId("policy-a")
                        .matchedItems(List.of(ResolvedPromptItem.builder()
                                .itemId(1L)
                                .versionId(11L)
                                .key("persona.maid.gentle_v1")
                                .matchReason("KEYWORD_ONLY")
                                .policyApplied(true)
                                .build()))
                        .rejectedItems(List.of())
                        .slotMapping(Map.of())
                        .build());

        PromptPreviewServiceImpl service = new PromptPreviewServiceImpl(resolverService);
        Map<String, Object> payload = service.previewMatch(PromptResolveContext.builder().build());
        List<Map<String, Object>> matchedItems = (List<Map<String, Object>>) payload.get("matchedItems");
        assertNotNull(matchedItems);
        assertEquals(1, matchedItems.size());
        assertEquals("KEYWORD_ONLY", matchedItems.get(0).get("matchReason"));
        assertEquals("KEYWORD_ONLY", matchedItems.get(0).get("reason"));
        assertEquals(true, matchedItems.get(0).get("policyApplied"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewAssembleShouldExposeFilteredOutItemsWithReason() {
        PromptResolverService resolverService = mock(PromptResolverService.class);
        when(resolverService.resolve(org.mockito.ArgumentMatchers.any(PromptResolveContext.class)))
                .thenReturn(PromptResolveResult.builder()
                        .policyId("policy-a")
                        .matchedItems(List.of(
                                ResolvedPromptItem.builder()
                                        .itemId(1L)
                                        .versionId(11L)
                                        .key("persona.maid.gentle_v1")
                                        .value("persona text")
                                        .runtimeSlot("instructions.persona")
                                        .matchReason("KEYWORD_ONLY")
                                        .build(),
                                ResolvedPromptItem.builder()
                                        .itemId(2L)
                                        .versionId(12L)
                                        .key("mcp.unknown.slot_v1")
                                        .value("not rendered")
                                        .runtimeSlot("mcp.workflow")
                                        .matchReason("KEYWORD_ONLY")
                                        .build()
                        ))
                        .rejectedItems(List.of())
                        .slotMapping(Map.of(
                                "instructions.persona", List.of(ResolvedPromptItem.builder()
                                        .itemId(1L)
                                        .versionId(11L)
                                        .key("persona.maid.gentle_v1")
                                        .value("persona text")
                                        .runtimeSlot("instructions.persona")
                                        .matchReason("KEYWORD_ONLY")
                                        .build()),
                                "mcp.workflow", List.of(ResolvedPromptItem.builder()
                                        .itemId(2L)
                                        .versionId(12L)
                                        .key("mcp.unknown.slot_v1")
                                        .value("not rendered")
                                        .runtimeSlot("mcp.workflow")
                                        .matchReason("KEYWORD_ONLY")
                                        .build())
                        ))
                        .build());

        PromptPreviewServiceImpl service = new PromptPreviewServiceImpl(resolverService);
        Map<String, Object> payload = service.previewAssemble(PromptResolveContext.builder().build());
        List<Map<String, Object>> filteredOutItems = (List<Map<String, Object>>) payload.get("filteredOutItems");
        assertNotNull(filteredOutItems);
        assertEquals(1, filteredOutItems.size());
        assertEquals("mcp.unknown.slot_v1", filteredOutItems.get(0).get("key"));
        assertEquals("UNKNOWN_RUNTIME_SLOT", filteredOutItems.get(0).get("filteredReason"));
    }
}
