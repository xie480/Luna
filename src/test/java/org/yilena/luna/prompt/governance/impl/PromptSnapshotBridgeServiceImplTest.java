package org.yilena.luna.prompt.governance.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.prompt.governance.PromptPolicyService;
import org.yilena.luna.prompt.governance.entity.PromptPolicyEntity;
import org.yilena.luna.prompt.governance.entity.PromptRuntimeSnapshotRefEntity;
import org.yilena.luna.prompt.governance.mapper.PromptRuntimeSnapshotRefMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptSnapshotBridgeServiceImplTest {

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
}
