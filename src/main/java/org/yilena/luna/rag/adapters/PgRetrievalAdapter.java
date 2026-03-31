package org.yilena.luna.rag.adapters;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.mapper.KnowledgeBaseMapper;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PgRetrievalAdapter {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public List<KnowledgeChunkRecord> searchKnowledgeByVector(String vector, int topK) {
        return knowledgeBaseMapper.searchByVector(vector, topK);
    }
}
