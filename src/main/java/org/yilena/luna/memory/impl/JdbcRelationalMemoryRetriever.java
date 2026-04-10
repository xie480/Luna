package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.mapper.RuntimeReadMapper;
import org.yilena.luna.memory.RelationalMemoryRetriever;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
/**
 * 基于 JDBC 的关系记忆检索器，负责读取关系工作记忆、画像、边界规则及其语义记忆，
 * 为关系推理和回复调性控制提供数据输入。
 */
public class JdbcRelationalMemoryRetriever implements RelationalMemoryRetriever {

    private final RuntimeReadMapper runtimeReadMapper;
    private final LlmClientUtil llmClientUtil;

    @Override
    /**
     * 检索当前会话的关系侧上下文，必要时再补充语义检索结果。
     */
    public Map<String, Object> retrieve(String sessionId, String semanticQuery, RelationalRuntimeState relationalState) {
        Map<String, Object> result = new HashMap<>();
        /**
         * 先加载近端关系上下文，包括工作记忆、画像、情绪基线、边界规则和感知缓冲，
         * 优先满足大部分关系推理场景。
         */
        Map<String, Object> workingMemory = queryOne(() -> runtimeReadMapper.selectRelationalWorkingMemory(sessionId));
        Map<String, Object> profile = queryOne(() -> runtimeReadMapper.selectRelationalProfile(sessionId));
        Map<String, Object> emotionalBaseline = queryOne(() -> runtimeReadMapper.selectEmotionalBaseline(sessionId));
        List<Map<String, Object>> boundaryRules = queryList(() -> runtimeReadMapper.selectBoundaryRules(sessionId));

        result.put("working_memory", workingMemory);
        result.put("profile", profile);
        result.put("emotional_baseline", emotionalBaseline);
        result.put("boundary_rules", boundaryRules);
        List<Map<String, Object>> relationalPerceptualBuffer = queryList(() -> runtimeReadMapper.selectRelationalPerceptualBuffer(sessionId, 10));
        result.put("relational_perceptual_buffer", relationalPerceptualBuffer);

        boolean semanticRetrievalEnabled = shouldUseSemanticRetrieval(
                relationalState,
                semanticQuery,
                workingMemory,
                profile,
                emotionalBaseline,
                boundaryRules,
                relationalPerceptualBuffer
        );
        String queryVector = semanticRetrievalEnabled ? queryVector(semanticQuery) : null;
        result.put("semantic_retrieval_enabled", semanticRetrievalEnabled);

        /**
         * 只有在关系态敏感或近端上下文不足时才启用向量语义检索，
         * 控制检索成本并避免无意义扩展上下文。
         */
        if (semanticRetrievalEnabled) {
            result.put("semantic_facts", queryList(() -> runtimeReadMapper.selectRelationalSemanticFacts(sessionId, queryVector)));
            result.put("episodes", queryList(() -> runtimeReadMapper.selectRelationalEpisodes(sessionId, queryVector)));
            result.put("procedures", queryList(() -> runtimeReadMapper.selectRelationalProcedures(queryVector)));
        } else {
            result.put("semantic_facts", Collections.emptyList());
            result.put("episodes", Collections.emptyList());
            result.put("procedures", Collections.emptyList());
        }
        return result;
    }

    private boolean shouldUseSemanticRetrieval(RelationalRuntimeState relationalState,
                                               String semanticQuery,
                                               Map<String, Object> workingMemory,
                                               Map<String, Object> profile,
                                               Map<String, Object> emotionalBaseline,
                                               List<Map<String, Object>> boundaryRules,
                                               List<Map<String, Object>> relationalPerceptualBuffer) {
        if (relationalState == RelationalRuntimeState.DEEP_TALK
                || relationalState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationalState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationalState == RelationalRuntimeState.REPAIRING) {
            return true;
        }

        if (containsAny(semanticQuery,
                "remember", "previous", "preference", "boundary", "support style", "address me",
                "记得", "之前", "上次", "偏好", "边界", "称呼", "安慰", "支持方式", "别叫我", "关系")) {
            return true;
        }

        boolean hasNearContext = !(workingMemory == null || workingMemory.isEmpty())
                || !(profile == null || profile.isEmpty())
                || !(emotionalBaseline == null || emotionalBaseline.isEmpty())
                || (boundaryRules != null && !boundaryRules.isEmpty())
                || (relationalPerceptualBuffer != null && !relationalPerceptualBuffer.isEmpty());
        return !hasNearContext;
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

    @FunctionalInterface
    private interface SqlOneSupplier {
        Map<String, Object> get();
    }

    @FunctionalInterface
    private interface SqlListSupplier {
        List<Map<String, Object>> get();
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

