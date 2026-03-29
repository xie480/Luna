package org.yilena.luna.rag.adapters;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.entity.Memory;
import org.yilena.luna.entity.UserPreference;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.mapper.UserPreferenceMapper;

import java.util.List;

@Component
@RequiredArgsConstructor
/**
 * PG 数据访问适配器。
 * 将 RAG 层与 mapper 细节解耦，减少上层直接依赖数据库实现。
 */
public class PgRetrievalAdapter {

    // 三类数据源 mapper
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MemoryMapper memoryMapper;
    private final UserPreferenceMapper userPreferenceMapper;

    /**
     * knowledge 向量召回。
     */
    public List<KnowledgeBase> searchKnowledgeByVector(String vector, int topK) {
        return knowledgeBaseMapper.searchByVector(vector, topK);
    }

    /**
     * memory 向量召回；优先按 session_id 过滤，保证记忆隔离性。
     */
    public List<Memory> searchMemoryByVector(String vector, String sessionId, int topK) {
        if (sessionId != null && !sessionId.isBlank()) {
            return memoryMapper.searchByVectorAndSessionId(vector, sessionId, topK);
        }
        return memoryMapper.searchByVector(vector, topK);
    }

    /**
     * preference 向量召回。
     */
    public List<UserPreference> searchPreferenceByVector(String vector, int topK) {
        return userPreferenceMapper.searchByVector(vector, topK);
    }
}
