package org.yilena.luna.memory.impl;

import org.junit.jupiter.api.Test;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.properties.RuntimeAuditReplayProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcRuntimeRetrieverReplayModeTest {

    @Test
    void shouldDefaultToFullReplayMode() {
        RuntimeAuditReplayProperty replayProperty = new RuntimeAuditReplayProperty();
        assertTrue(replayProperty.fullReplayMode());
    }

    @Test
    void shouldUseFullQueryWhenReplayModeIsFull() {
        RuntimeReadMapper runtimeReadMapper = mock(RuntimeReadMapper.class);
        MemoryHotLayerService memoryHotLayerService = mock(MemoryHotLayerService.class);
        RuntimeAuditReplayProperty replayProperty = new RuntimeAuditReplayProperty();
        replayProperty.setReplayMode("full");

        when(memoryHotLayerService.getSessionCache("s-1")).thenReturn(Map.of());
        when(memoryHotLayerService.getLatestPendingToolCall("s-1")).thenReturn(Map.of());
        when(runtimeReadMapper.selectRuntimeSession("s-1")).thenReturn(Map.of("session_id", "s-1"));
        when(runtimeReadMapper.selectRuntimeRecentMessages("s-1")).thenReturn(List.of());
        when(runtimeReadMapper.selectRuntimeToolResultsFull("s-1")).thenReturn(List.of(Map.of("trace_id", 1L)));
        when(runtimeReadMapper.selectRuntimeContextSnapshotsFull("s-1")).thenReturn(List.of(Map.of("id", 2L)));

        JdbcRuntimeRetriever retriever = new JdbcRuntimeRetriever(runtimeReadMapper, memoryHotLayerService, replayProperty);
        Map<String, Object> result = retriever.retrieve("s-1");

        assertNotNull(result);
        assertEquals(1, ((List<?>) result.get("active_tool_results")).size());
        verify(runtimeReadMapper).selectRuntimeToolResultsFull(eq("s-1"));
        verify(runtimeReadMapper).selectRuntimeContextSnapshotsFull(eq("s-1"));
        verify(runtimeReadMapper, never()).selectRuntimeToolResultsWindow(eq("s-1"), anyInt());
        verify(runtimeReadMapper, never()).selectRuntimeContextSnapshotsWindow(eq("s-1"), anyInt());
    }
}
