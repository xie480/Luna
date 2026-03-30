package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.TaskMemoryRetriever;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcTaskMemoryRetriever implements TaskMemoryRetriever {

    private final RuntimeReadMapper runtimeReadMapper;

    @Override
    public Map<String, Object> retrieve(String sessionId, String userInput) {
        Map<String, Object> result = new HashMap<>();
        result.put("working_memory", queryOne(() -> runtimeReadMapper.selectTaskWorkingMemory(sessionId)));
        result.put("task_facts", queryList(() -> runtimeReadMapper.selectTaskSemanticFacts(sessionId)));
        result.put("task_episodes", queryList(() -> runtimeReadMapper.selectTaskEpisodes(sessionId)));
        result.put("task_procedures", queryList(runtimeReadMapper::selectTaskProcedures));
        result.put("knowledge", queryList(runtimeReadMapper::selectKnowledgeChunks));
        result.put("plan_context", queryOne(() -> runtimeReadMapper.selectLatestPlanContext(sessionId)));
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
