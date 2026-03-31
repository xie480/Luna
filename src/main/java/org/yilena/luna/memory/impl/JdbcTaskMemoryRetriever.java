package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.MemoryHotLayerService;
import org.yilena.luna.memory.TaskMemoryRetriever;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcTaskMemoryRetriever implements TaskMemoryRetriever {

    private final RuntimeReadMapper runtimeReadMapper;
    private final LlmClientUtil llmClientUtil;
    private final MemoryHotLayerService memoryHotLayerService;

    @Override
    public Map<String, Object> retrieve(String sessionId, String userInput) {
        Map<String, Object> result = new HashMap<>();
        String queryVector = queryVector(userInput);
        Map<String, Object> workingCached = memoryHotLayerService.getWorkingMemoryCache(sessionId);
        Map<String, Object> workingMemory;
        List<Map<String, Object>> workingSlots;
        Map<String, Object> planContext;
        if (workingCached.isEmpty()) {
            workingMemory = queryOne(() -> runtimeReadMapper.selectTaskWorkingMemory(sessionId));
            workingSlots = queryList(() -> runtimeReadMapper.selectTaskWorkingSlots(sessionId));
            planContext = queryOne(() -> runtimeReadMapper.selectLatestPlanContext(sessionId));
            Map<String, Object> hotPayload = new HashMap<>();
            hotPayload.put("working_memory", workingMemory);
            hotPayload.put("working_slots", workingSlots);
            hotPayload.put("plan_context", planContext);
            memoryHotLayerService.putWorkingMemoryCache(sessionId, hotPayload);
        } else {
            workingMemory = asMap(workingCached.get("working_memory"));
            workingSlots = asList(workingCached.get("working_slots"));
            planContext = asMap(workingCached.get("plan_context"));
        }

        result.put("working_memory", workingMemory);
        result.put("working_slots", workingSlots);
        result.put("task_facts", queryList(() -> runtimeReadMapper.selectTaskSemanticFacts(sessionId, queryVector)));
        result.put("task_episodes", queryList(() -> runtimeReadMapper.selectTaskEpisodes(sessionId, queryVector)));
        result.put("task_episode_steps", queryList(() -> runtimeReadMapper.selectTaskEpisodeSteps(sessionId)));
        result.put("task_procedures", queryList(() -> runtimeReadMapper.selectTaskProcedures(queryVector)));
        result.put("knowledge", queryList(() -> runtimeReadMapper.selectKnowledgeChunks(queryVector)));
        result.put("plan_context", planContext);
        return result;
    }

    private String queryVector(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return null;
        }
        try {
            return llmClientUtil.getEmbedding(userInput);
        } catch (Exception ignore) {
            return null;
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : Collections.emptyList();
    }
}
