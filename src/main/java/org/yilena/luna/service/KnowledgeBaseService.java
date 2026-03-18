package org.yilena.luna.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.yilena.luna.entity.KnowledgeBase;
import org.yilena.luna.enums.SourceType;

import java.util.List;

/**
 * 知識庫服務接口
 */
public interface KnowledgeBaseService extends IService<KnowledgeBase> {

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
    List<KnowledgeBase> searchKnowledge(String query, int topK);
}
