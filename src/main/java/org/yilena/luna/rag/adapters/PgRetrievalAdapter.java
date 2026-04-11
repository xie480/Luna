package org.yilena.luna.rag.adapters;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mapper.RagMemoryMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 该数据访问适配器负责统一封装 PostgreSQL/pgvector 侧的知识、记忆与偏好检索入口。
 */
@Component
@RequiredArgsConstructor
public class PgRetrievalAdapter {

    /**
     * 知识库检索 Mapper。
     */
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    /**
     * 记忆与偏好检索 Mapper。
     */
    private final RagMemoryMapper ragMemoryMapper;

    /**
     * 基于向量相似度检索知识分片。
     */
    public List<KnowledgeChunkRecord> searchKnowledgeByVector(String vector, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByVector(vector, topK, sourceTypes);
    }

    /**
     * 基于精确匹配检索知识分片。
     */
    public List<KnowledgeChunkRecord> searchKnowledgeByExact(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByExact(query, topK, sourceTypes);
    }

    /**
     * 基于全文检索检索知识分片。
     */
    public List<KnowledgeChunkRecord> searchKnowledgeByFts(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByFts(query, topK, sourceTypes);
    }

    /**
     * 基于关键词检索知识分片。
     */
    public List<KnowledgeChunkRecord> searchKnowledgeByKeyword(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByKeyword(query, topK, sourceTypes);
    }

    /**
     * 基于 trigram 相似度检索知识分片。
     */
    public List<KnowledgeChunkRecord> searchKnowledgeByTrigram(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByTrigram(query, topK, sourceTypes);
    }

    /**
     * 按向量和时间窗口检索会话记忆。
     */
    public List<Map<String, Object>> searchMemoryByVector(
            String sessionId,
            String queryVector,
            List<String> memoryTypes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int topK
    ) {
        return ragMemoryMapper.selectMemoryByVector(sessionId, queryVector, memoryTypes, startTime, endTime, topK);
    }

    /**
     * 按关键词和时间窗口检索会话记忆。
     */
    public List<Map<String, Object>> searchMemoryByKeyword(
            String sessionId,
            String keyword,
            List<String> memoryTypes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            int topK
    ) {
        return ragMemoryMapper.selectMemoryByKeyword(sessionId, keyword, memoryTypes, startTime, endTime, topK);
    }

    /**
     * 基于向量检索用户偏好。
     */
    public List<Map<String, Object>> searchPreferenceByVector(String queryVector, int topK) {
        return ragMemoryMapper.selectPreferenceByVector(queryVector, topK);
    }

    /**
     * 基于精确键或 trigram 相似度检索用户偏好。
     */
    public List<Map<String, Object>> searchPreferenceByExactOrTrigram(String prefKey, String keyword, int topK) {
        return ragMemoryMapper.selectPreferenceByExactOrTrigram(prefKey, keyword, topK);
    }
}
