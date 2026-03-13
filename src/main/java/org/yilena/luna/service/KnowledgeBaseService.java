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
     * 將文本內容分片、向量化並存入知識庫
     *
     * @param title      標題或文件名
     * @param content    完整的文本內容
     * @param sourceType 來源類型
     * @param sourcePath 來源路徑或標識
     */
    void addKnowledge(String title, String content, SourceType sourceType, String sourcePath);

    /**
     * 根據用戶問題進行向量檢索，獲取最相關的知識片段
     *
     * @param query 用戶問題
     * @param topK  返回的最大片段數量
     * @return 相關的知識庫記錄列表
     */
    List<KnowledgeBase> searchKnowledge(String query, int topK);
}
