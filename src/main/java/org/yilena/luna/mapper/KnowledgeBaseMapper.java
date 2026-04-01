package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.KnowledgeChunkRecord;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgeBaseMapper {

    @Select("""
            select
                kc.chunk_id as id,
                kd.doc_id as doc_id,
                kc.chunk_id as chunk_id,
                kc.chunk_order as chunk_order,
                kd.title as title,
                kc.chunk_text as content,
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end as source_type,
                kd.source_uri as source_path,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where kc.embedding is not null
            order by kc.embedding::vector <-> #{vector}::vector
            limit #{topK}
            """)
    List<KnowledgeChunkRecord> searchByVector(@Param("vector") String vector, @Param("topK") int topK);

    @Select("""
            <script>
            select
                kc.chunk_id as id,
                kd.doc_id as doc_id,
                kc.chunk_id as chunk_id,
                kc.chunk_order as chunk_order,
                kd.title as title,
                kc.chunk_text as content,
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end as source_type,
                kd.source_uri as source_path,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at,
                1 - (kc.embedding &lt;=&gt; #{vector}::vector) as vector_score
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where kc.embedding is not null
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by kc.embedding &lt;=&gt; #{vector}::vector
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByVector(@Param("vector") String vector,
                                                          @Param("topK") int topK,
                                                          @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            <script>
            select
                kc.chunk_id as id,
                kd.doc_id as doc_id,
                kc.chunk_id as chunk_id,
                kc.chunk_order as chunk_order,
                kd.title as title,
                kc.chunk_text as content,
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end as source_type,
                kd.source_uri as source_path,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at,
                case
                    when lower(coalesce(kd.title, '')) = lower(#{query})
                      or lower(coalesce(kc.chunk_text, '')) = lower(#{query}) then 1.0
                    else 0.0
                end as fts_score
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where (
                lower(coalesce(kd.title, '')) = lower(#{query})
                or lower(coalesce(kc.chunk_text, '')) = lower(#{query})
            )
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by kc.created_at desc
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByExact(@Param("query") String query,
                                                         @Param("topK") int topK,
                                                         @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            <script>
            select
                kc.chunk_id as id,
                kd.doc_id as doc_id,
                kc.chunk_id as chunk_id,
                kc.chunk_order as chunk_order,
                kd.title as title,
                kc.chunk_text as content,
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end as source_type,
                kd.source_uri as source_path,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at,
                ts_rank(
                  to_tsvector('simple', coalesce(kd.title, '') || ' ' || coalesce(kc.chunk_text, '')),
                  plainto_tsquery('simple', #{query})
                ) as fts_score
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where to_tsvector('simple', coalesce(kd.title, '') || ' ' || coalesce(kc.chunk_text, ''))
                  @@ plainto_tsquery('simple', #{query})
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by fts_score desc, kc.created_at desc
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByFts(@Param("query") String query,
                                                       @Param("topK") int topK,
                                                       @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            <script>
            select
                kc.chunk_id as id,
                kd.doc_id as doc_id,
                kc.chunk_id as chunk_id,
                kc.chunk_order as chunk_order,
                kd.title as title,
                kc.chunk_text as content,
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end as source_type,
                kd.source_uri as source_path,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at,
                case
                    when coalesce(kd.title, '') ilike concat('%', #{query}, '%') then 1.0
                    when coalesce(kc.chunk_text, '') ilike concat('%', #{query}, '%') then 0.8
                    else 0.0
                end as fts_score
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where (
                coalesce(kd.title, '') ilike concat('%', #{query}, '%')
                or coalesce(kc.chunk_text, '') ilike concat('%', #{query}, '%')
            )
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(kd.source_type, '')) = 'FILE' then 0
                    when upper(coalesce(kd.source_type, '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(kd.source_type, '')) = 'MANUAL_INPUT' then 2
                    when kd.source_type ~ '^[0-9]+$' then cast(kd.source_type as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by fts_score desc, kc.created_at desc
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByKeyword(@Param("query") String query,
                                                           @Param("topK") int topK,
                                                           @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            select count(1)
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where (#{title} is null or #{title} = '' or kd.title ilike concat('%', #{title}, '%'))
              and (#{content} is null or #{content} = '' or kc.chunk_text ilike concat('%', #{content}, '%'))
              and (#{sourceType} is null or #{sourceType} = '' or upper(kd.source_type) = upper(#{sourceType}))
              and (#{sourcePath} is null or #{sourcePath} = '' or kd.source_uri ilike concat('%', #{sourcePath}, '%'))
              and (#{startTime} is null or kc.created_at >= #{startTime})
              and (#{endTime} is null or kc.created_at <= #{endTime})
            """)
    Long countByFilters(@Param("title") String title,
                        @Param("content") String content,
                        @Param("sourceType") String sourceType,
                        @Param("sourcePath") String sourcePath,
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

    @Select("""
            select
                kc.chunk_id as id,
                kd.doc_id as doc_id,
                kc.chunk_id as chunk_id,
                kc.chunk_order as chunk_order,
                kd.title as title,
                kc.chunk_text as content,
                kd.source_type as source_type,
                kd.source_uri as source_path,
                kc.embedding as embedding,
                kc.created_at as created_at,
                kc.created_at as updated_at
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            where (#{title} is null or #{title} = '' or kd.title ilike concat('%', #{title}, '%'))
              and (#{content} is null or #{content} = '' or kc.chunk_text ilike concat('%', #{content}, '%'))
              and (#{sourceType} is null or #{sourceType} = '' or upper(kd.source_type) = upper(#{sourceType}))
              and (#{sourcePath} is null or #{sourcePath} = '' or kd.source_uri ilike concat('%', #{sourcePath}, '%'))
              and (#{startTime} is null or kc.created_at >= #{startTime})
              and (#{endTime} is null or kc.created_at <= #{endTime})
            order by kc.created_at desc
            limit #{limit} offset #{offset}
            """)
    List<KnowledgeChunkRecord> selectByFilters(@Param("title") String title,
                                               @Param("content") String content,
                                               @Param("sourceType") String sourceType,
                                               @Param("sourcePath") String sourcePath,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("limit") long limit,
                                               @Param("offset") long offset);

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
