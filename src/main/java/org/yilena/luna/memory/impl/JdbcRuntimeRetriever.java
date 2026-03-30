package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.RuntimeRetriever;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcRuntimeRetriever implements RuntimeRetriever {

    private final RuntimeReadMapper runtimeReadMapper;

    @Override
    public Map<String, Object> retrieve(String sessionId) {
        Map<String, Object> result = new HashMap<>();
        result.put("session", queryOne(() -> runtimeReadMapper.selectRuntimeSession(sessionId)));
        result.put("recent_messages", queryList(() -> runtimeReadMapper.selectRuntimeRecentMessages(sessionId)));
        result.put("active_tool_results", queryList(() -> runtimeReadMapper.selectRuntimeToolResults(sessionId)));
        result.put("context_snapshots", queryList(() -> runtimeReadMapper.selectRuntimeContextSnapshots(sessionId)));
        return result;
    }

    private Map<String, Object> queryOne(SqlOneSupplier supplier) {
        try {
            Map<String, Object> row = supplier.get();
            return row == null ? Collections.emptyMap() : row;
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> queryList(SqlListSupplier supplier) {
        try {
            List<Map<String, Object>> rows = supplier.get();
            return rows == null ? Collections.emptyList() : rows;
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }

    @FunctionalInterface
    private interface SqlOneSupplier {
        Map<String, Object> get();
    }

    @FunctionalInterface
    private interface SqlListSupplier {
        List<Map<String, Object>> get();
    }
}
