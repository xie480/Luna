package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.yilena.luna.entity.KnowledgeBase;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 向量檢索 (依賴 PGVector 插件)
     * 需要在 XML 中實現具體的 SQL 邏輯，例如:
     * SELECT * FROM knowledge_base ORDER BY embedding <-> #{vector} LIMIT #{topK}
     * 
     * @param vector 向量字符串 (格式: "[0.1, 0.2, ...]")
     * @param topK 返回數量
     * @return 匹配的記錄
     */
    List<KnowledgeBase> searchByVector(@Param("vector") String vector, @Param("topK") int topK);
}
