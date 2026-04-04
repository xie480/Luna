package org.yilena.luna.state.store.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yilena.luna.mapper.RuntimeAuditMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextSnapshotStoreImplTest {

    @Test
    void shouldPersistActiveRefsInFinalSnapshotPayload() throws Exception {
        RuntimeAuditMapper mapper = mock(RuntimeAuditMapper.class);
        when(mapper.insertContextSnapshotAndReturnId(anyString(), anyLong(), anyLong(), anyString())).thenReturn(99L);
        ContextSnapshotStoreImpl store = new ContextSnapshotStoreImpl(mapper, new ObjectMapper());

        store.saveFinalSnapshot(
                "s-1",
                1L,
                2L,
                null,
                "prompt",
                Map.of("A", 10),
                Map.of("A", 0.5),
                Map.of(),
                Map.of(
                        "activeKnowledgeRefs", List.of("k1"),
                        "activeMemoryRefs", List.of("m1"),
                        "activeToolEvidenceRefs", List.of("t1"),
                        "activeMcpPromptRefs", List.of("p1"),
                        "activeMcpResourceRefs", List.of("r1")
                )
        );

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertContextSnapshotAndReturnId(anyString(), anyLong(), anyLong(), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"snapshotType\":\"FINAL_MODEL_CONTEXT\""));
        assertTrue(payload.contains("\"activeRefs\""));
        assertTrue(payload.contains("\"activeKnowledgeRefs\":[\"k1\"]"));
        assertTrue(payload.contains("\"activeMcpResourceRefs\":[\"r1\"]"));
        assertTrue(payload.contains("\"activeToolEvidenceRefs\":[\"t1\"]"));
    }
}
