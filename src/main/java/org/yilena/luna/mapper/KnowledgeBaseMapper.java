package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
    @Select("""
            select
                kc.chunk_id as id,
                kd.title as title,
                kc.chunk_text as content,
                kd.source_type as source_type,
                kd.source_uri as source_path,
                cast(kd.doc_id as varchar) as vector_id,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where kc.embedding is not null
            order by kc.embedding::vector <-> #{vector}::vector
            limit #{topK}
            """)
    List<KnowledgeBase> searchByVector(@Param("vector") String vector, @Param("topK") int topK);

    @Insert("""
            insert into knowledge_document(owner_scope, owner_ref, source_type, source_uri, title, metadata_json, created_at)
            values ('GLOBAL', null, #{sourceType}, #{sourcePath}, #{title}, jsonb_build_object('source','knowledge_mq'), current_timestamp)
            returning doc_id
            """)
    Long insertKnowledgeDocument(@Param("title") String title,
                                 @Param("sourceType") String sourceType,
                                 @Param("sourcePath") String sourcePath);

    @Insert("""
            insert into knowledge_chunk(doc_id, chunk_order, chunk_text, chunk_summary, keywords_json, embedding, metadata_json, created_at)
            values (#{docId}, #{chunkOrder}, #{chunkText}, #{chunkSummary}, cast(#{keywordsJson} as jsonb), #{embedding}::vector,
                    jsonb_build_object('source','knowledge_mq'), current_timestamp)
            """)
    int insertKnowledgeChunk(@Param("docId") Long docId,
                             @Param("chunkOrder") int chunkOrder,
                             @Param("chunkText") String chunkText,
                             @Param("chunkSummary") String chunkSummary,
                             @Param("keywordsJson") String keywordsJson,
                             @Param("embedding") String embedding);
}
