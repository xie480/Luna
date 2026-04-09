package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.entity.PromptRuntimeSnapshotRefEntity;
import org.yilena.luna.prompt.governance.mapper.PromptRuntimeSnapshotRefMapper;
import org.yilena.luna.prompt.governance.model.PromptResolveResult;
import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptSnapshotBridgeServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildSnapshotPayloadShouldContainPromptAliasFields() {
        PromptRuntimeSnapshotRefMapper mapper = mock(PromptRuntimeSnapshotRefMapper.class);
        PromptPolicyService policyService = mock(PromptPolicyService.class);
        PromptSnapshotBridgeServiceImpl service = new PromptSnapshotBridgeServiceImpl(mapper, policyService);
        Map<String, Object> payload = service.buildSnapshotPayload(
                PromptResolveResult.builder()
                        .policyId("chat_default_v1")
                        .matchedItems(List.of(ResolvedPromptItem.builder()
                                .itemId(11L)
                                .versionId(22L)
                                .key("persona.maid.gentle_v1")
                                .version("1.0.0")
                                .runtimeSlot("instructions.persona")
                                .build()))
                        .slotMapping(Map.of())
                        .build(),
                "chat_default_v1"
        );

        List<Map<String, Object>> refs = (List<Map<String, Object>>) payload.get("promptRefs");
        assertNotNull(refs);
        assertEquals(1, refs.size());
        assertEquals(11L, refs.get(0).get("itemId"));
        assertEquals(11L, refs.get(0).get("promptItemId"));
        assertEquals(22L, refs.get(0).get("versionId"));
        assertEquals(22L, refs.get(0).get("promptItemVersionId"));
        assertEquals("persona.maid.gentle_v1", refs.get(0).get("key"));
        assertEquals("persona.maid.gentle_v1", refs.get(0).get("promptKey"));
        assertEquals("1.0.0", refs.get(0).get("version"));
        assertEquals("1.0.0", refs.get(0).get("promptVersion"));
    }

    @Test
    void persistSnapshotRefsShouldWritePolicyIdAndPolicyKeyAndPolicyApplied() {
        PromptRuntimeSnapshotRefMapper mapper = mock(PromptRuntimeSnapshotRefMapper.class);
        PromptPolicyService policyService = mock(PromptPolicyService.class);
        when(policyService.getByPolicyId("chat_default_v1"))
                .thenReturn(PromptPolicyEntity.builder().id(123L).policyKey("chat_default_v1").build());

        PromptSnapshotBridgeServiceImpl service = new PromptSnapshotBridgeServiceImpl(mapper, policyService);
        Map<String, Object> payload = Map.of(
                "policyId", "chat_default_v1",
                "assemblerVersion", "assembler.v1",
                "promptRefs", List.of(Map.of(
                        "itemId", 11L,
                        "versionId", 22L,
                        "key", "persona.maid.gentle_v1",
                        "version", "1.0.0",
                        "runtimeSlot", "instructions.persona",
                        "matchReason", "KEYWORD_ONLY",
                        "policyApplied", true,
                        "value", "prompt-value"
                ))
        );

        service.persistSnapshotRefs("s1", 1L, 2L, "snap-1", payload);

        ArgumentCaptor<PromptRuntimeSnapshotRefEntity> captor = ArgumentCaptor.forClass(PromptRuntimeSnapshotRefEntity.class);
        verify(mapper).insert(captor.capture());
        PromptRuntimeSnapshotRefEntity saved = captor.getValue();
        assertEquals(123L, saved.getPolicyId());
        assertEquals("chat_default_v1", saved.getPolicyKey());
        assertTrue(Boolean.TRUE.equals(saved.getPolicyApplied()));
        assertEquals("KEYWORD_ONLY", saved.getMatchReason());
    }

    @Test
    void persistSnapshotRefsShouldPreferPromptAliasFields() {
        PromptRuntimeSnapshotRefMapper mapper = mock(PromptRuntimeSnapshotRefMapper.class);
        PromptPolicyService policyService = mock(PromptPolicyService.class);
        when(policyService.getByPolicyId("chat_default_v1"))
                .thenReturn(PromptPolicyEntity.builder().id(123L).policyKey("chat_default_v1").build());

        PromptSnapshotBridgeServiceImpl service = new PromptSnapshotBridgeServiceImpl(mapper, policyService);
        Map<String, Object> payload = Map.of(
                "policyId", "chat_default_v1",
                "assemblerVersion", "assembler.v1",
                "promptRefs", List.of(Map.of(
                        "promptItemId", 31L,
                        "promptItemVersionId", 41L,
                        "promptKey", "persona.maid.gentle_v2",
                        "promptVersion", "2.0.0",
                        "runtimeSlot", "instructions.persona",
                        "matchReason", "POLICY_INCLUDE",
                        "policyApplied", true,
                        "value", "prompt-value"
                ))
        );

        service.persistSnapshotRefs("s1", 1L, 2L, "snap-1", payload);

        ArgumentCaptor<PromptRuntimeSnapshotRefEntity> captor = ArgumentCaptor.forClass(PromptRuntimeSnapshotRefEntity.class);
        verify(mapper).insert(captor.capture());
        PromptRuntimeSnapshotRefEntity saved = captor.getValue();
        assertEquals(31L, saved.getPromptItemId());
        assertEquals(41L, saved.getPromptItemVersionId());
        assertEquals("persona.maid.gentle_v2", saved.getPromptKey());
        assertEquals("2.0.0", saved.getPromptVersionNo());
    }
}
