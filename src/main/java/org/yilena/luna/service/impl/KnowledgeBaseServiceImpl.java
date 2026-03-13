package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.rag.TextSplitter;
import org.yilena.luna.service.KnowledgeBaseService;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;

/**
 * 知識庫服務實現類
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> implements KnowledgeBaseService {

    private final LlmClientUtil llmClientUtil;

    @Override
    public void addKnowledge(String title, String content, SourceType sourceType, String sourcePath) {
        // 1. 文本分片 (每段 500 字，重疊 50 字)
        List<String> chunks = TextSplitter.splitText(content, 500, 50);
        log.info("開始處理知識庫寫入，標題: {}, 總分片數: {}", title, chunks.size());

        int successCount = 0;
        for (String chunk : chunks) {
            try {
                // 2. 調用大模型獲取 Embedding 向量
                List<Double> vector = llmClientUtil.getEmbedding(chunk);
                
                if (vector != null && !vector.isEmpty()) {
                    // 3. 構建實體並保存到 PostgreSQL (PGVector)
                    KnowledgeBase kb = KnowledgeBase.builder()
                            .title(title)
                            .content(chunk)
                            .sourceType(sourceType)
                            .sourcePath(sourcePath)
                            .embedding(vector.toString()) // 轉換為 PGVector 支持的字符串格式 "[0.1, 0.2, ...]"
                            .build();
                    
                    this.save(kb);
                    successCount++;
                } else {
                    log.warn("分片向量化失敗，跳過該分片。標題: {}", title);
                }
            } catch (Exception e) {
                log.error("寫入知識庫分片異常: {}", e.getMessage());
            }
        }
        log.info("知識庫寫入完成，成功寫入分片數: {}/{}", successCount, chunks.size());
    }

    @Override
    public List<KnowledgeBase> searchKnowledge(String query, int topK) {
        // 1. 將用戶的查詢問題向量化
        List<Double> queryVector = llmClientUtil.getEmbedding(query);
        
        if (queryVector == null || queryVector.isEmpty()) {
            log.warn("查詢問題向量化失敗，無法進行檢索: {}", query);
            return Collections.emptyList();
        }

        // 2. 調用自定義 Mapper 進行餘弦相似度檢索
        log.debug("開始向量檢索，TopK: {}", topK);
        return this.baseMapper.searchByVector(queryVector.toString(), topK);
    }
}
