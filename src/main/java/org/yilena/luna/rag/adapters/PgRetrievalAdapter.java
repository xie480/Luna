package org.yilena.luna.rag.adapters;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mapper.RagMemoryMapper;

import java.time.LocalDateTime;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PgRetrievalAdapter {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RagMemoryMapper ragMemoryMapper;

    public List<KnowledgeChunkRecord> searchKnowledgeByVector(String vector, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByVector(vector, topK, sourceTypes);
    }

    public List<KnowledgeChunkRecord> searchKnowledgeByExact(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByExact(query, topK, sourceTypes);
    }

    public List<KnowledgeChunkRecord> searchKnowledgeByFts(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByFts(query, topK, sourceTypes);
    }

    public List<KnowledgeChunkRecord> searchKnowledgeByKeyword(String query, int topK, List<Integer> sourceTypes) {
        return knowledgeBaseMapper.searchRagKnowledgeByKeyword(query, topK, sourceTypes);
    }

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

    public List<Map<String, Object>> searchPreferenceByVector(String queryVector, int topK) {
        return ragMemoryMapper.selectPreferenceByVector(queryVector, topK);
    }

    public List<Map<String, Object>> searchPreferenceByExactOrTrigram(String prefKey, String keyword, int topK) {
        return ragMemoryMapper.selectPreferenceByExactOrTrigram(prefKey, keyword, topK);
    }
}
