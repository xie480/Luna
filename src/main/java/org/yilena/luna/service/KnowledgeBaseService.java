package org.yilena.luna.service;

import org.yilena.luna.entity.KnowledgeChunkRecord;
import org.yilena.luna.enums.SourceType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知識庫服務接口
 */
public interface KnowledgeBaseService {

    /**
     * 添加知識到知識庫 (包含分片和向量化)
     * @param title 標題
     * @param content 內容
     * @param sourceType 來源類型
     * @param sourcePath 來源路徑
     */
    void addKnowledge(String title, String content, SourceType sourceType, String sourcePath);

    /**
     * 向量檢索知識庫
     * @param query 查詢語句
     * @param topK 返回數量
     * @return 匹配的知識列表
     */
    List<KnowledgeChunkRecord> searchKnowledge(String query, int topK);

    Long countKnowledge(String title,
                        String content,
                        String sourceType,
                        String sourcePath,
                        LocalDateTime startTime,
                        LocalDateTime endTime);

    List<KnowledgeChunkRecord> pageKnowledge(String title,
                                             String content,
                                             String sourceType,
                                             String sourcePath,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime,
                                             long pageNo,
                                             long pageSize);
}
