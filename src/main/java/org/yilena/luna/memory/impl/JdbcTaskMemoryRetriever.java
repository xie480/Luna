package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.TaskRuntimeState;
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
/**
 * 基于 JDBC 的任务记忆检索器，负责加载任务工作记忆、计划上下文、感知缓冲和语义记忆，
 * 为任务规划与执行阶段提供上下文支持。
 */
public class JdbcTaskMemoryRetriever implements TaskMemoryRetriever {

    private final RuntimeReadMapper runtimeReadMapper;
    private final LlmClientUtil llmClientUtil;
    private final MemoryHotLayerService memoryHotLayerService;

    @Override
    /**
     * 检索当前会话的任务侧上下文，并按需补充语义检索结果。
     */
    public Map<String, Object> retrieve(String sessionId, String semanticQuery, TaskRuntimeState taskState) {
        Map<String, Object> result = new HashMap<>();
        /**
         * 先优先命中任务工作记忆热缓存，避免频繁读取工作记忆与计划上下文。
         */
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
        List<Map<String, Object>> taskPerceptualBuffer = queryList(() -> runtimeReadMapper.selectTaskPerceptualBuffer(sessionId, 10));
        result.put("task_perceptual_buffer", taskPerceptualBuffer);
        result.put("task_episode_steps", queryList(() -> runtimeReadMapper.selectTaskEpisodeSteps(sessionId)));
        result.put("plan_context", planContext);

        boolean semanticRetrievalEnabled = shouldUseSemanticRetrieval(taskState, semanticQuery, workingMemory, workingSlots, planContext, taskPerceptualBuffer);
        String queryVector = semanticRetrievalEnabled ? queryVector(semanticQuery) : null;
        result.put("semantic_retrieval_enabled", semanticRetrievalEnabled);

        /**
         * 只有在任务阶段复杂或近端工作记忆不足时才触发语义检索，
         * 降低向量检索成本并聚焦真正需要的补充记忆。
         */
        if (semanticRetrievalEnabled) {
            result.put("task_facts", queryList(() -> runtimeReadMapper.selectTaskSemanticFacts(sessionId, queryVector)));
            result.put("task_episodes", queryList(() -> runtimeReadMapper.selectTaskEpisodes(sessionId, queryVector)));
            result.put("task_procedures", queryList(() -> runtimeReadMapper.selectTaskProcedures(queryVector)));
            result.put("knowledge", queryList(() -> runtimeReadMapper.selectKnowledgeChunks(queryVector)));
        } else {
            result.put("task_facts", Collections.emptyList());
            result.put("task_episodes", Collections.emptyList());
            result.put("task_procedures", Collections.emptyList());
            result.put("knowledge", Collections.emptyList());
        }
        return result;
    }

    private boolean shouldUseSemanticRetrieval(TaskRuntimeState taskState,
                                               String semanticQuery,
                                               Map<String, Object> workingMemory,
                                               List<Map<String, Object>> workingSlots,
                                               Map<String, Object> planContext,
                                               List<Map<String, Object>> taskPerceptualBuffer) {
        if (taskState == TaskRuntimeState.CONTEXT_BUILDING
                || taskState == TaskRuntimeState.PLANNING
                || taskState == TaskRuntimeState.REPLANNING
                || taskState == TaskRuntimeState.REFLECTING) {
            return true;
        }

        if (containsAny(semanticQuery,
                "remember", "previous", "history", "knowledge", "document", "reference", "best practice",
                "回忆", "之前", "上次", "历史", "经验", "案例", "知识", "文档", "参考", "规则", "偏好", "流程", "步骤")) {
            return true;
        }

        boolean hasWorkingContext = !(workingMemory == null || workingMemory.isEmpty())
                || (workingSlots != null && !workingSlots.isEmpty())
                || !(planContext == null || planContext.isEmpty())
                || (taskPerceptualBuffer != null && !taskPerceptualBuffer.isEmpty());
        return !hasWorkingContext;
    }

    private String queryVector(String semanticQuery) {
        if (semanticQuery == null || semanticQuery.isBlank()) {
            return null;
        }
        try {
            return llmClientUtil.getEmbedding(semanticQuery);
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

    /**
     * 单行 SQL 查询供应器，用于延迟执行并返回一条任务记忆记录。
     */
    @FunctionalInterface
    private interface SqlOneSupplier {
        Map<String, Object> get();
    }

    /**
     * 列表 SQL 查询供应器，用于延迟执行并返回多条任务记忆记录。
     */
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

    private boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        String lowerText = text.toLowerCase();
        for (String word : words) {
            if (word != null && !word.isBlank() && lowerText.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}

