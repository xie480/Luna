package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.KnowledgeBase;

import java.util.List;

/**
 * 知識庫數據訪問層
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 基於 PGVector 的餘弦相似度進行 Top-K 向量檢索
     * 使用 <=> 操作符計算餘弦距離 (Cosine Distance)，距離越小越相似
     *
     * @param vectorString 向量字符串，格式如 "[0.1, 0.2, ...]"
     * @param topK         返回的最相似結果數量
     * @return 檢索到的知識庫片段列表
     */
    @Select("SELECT id, title, content, source_type, source_path, vector_id, created_at, updated_at " +
            "FROM knowledge_base " +
            "ORDER BY embedding <=> cast(#{vectorString} as vector) " +
            "LIMIT #{topK}")
    List<KnowledgeBase> searchByVector(@Param("vectorString") String vectorString, @Param("topK") int topK);
}
