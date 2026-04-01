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
                kb.id as id,
                kb.id as chunk_id,
                1 as chunk_order,
                kb.title as title,
                kb.content as content,
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 'FILE'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 'WEB_SEARCH'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 'MANUAL_INPUT'
                    when coalesce(cast(kb.source_type as varchar), '') = '0' then 'FILE'
                    when coalesce(cast(kb.source_type as varchar), '') = '1' then 'WEB_SEARCH'
                    when coalesce(cast(kb.source_type as varchar), '') = '2' then 'MANUAL_INPUT'
                    else null
                end as source_type,
                kb.source_path as source_path,
                kb.embedding as embedding,
                kb.created_at as created_at,
                kb.updated_at as updated_at
            from knowledge_base kb
            where kb.embedding is not null
            order by kb.embedding::vector <-> #{vector}::vector
            limit #{topK}
            """)
    List<KnowledgeChunkRecord> searchByVector(@Param("vector") String vector, @Param("topK") int topK);

    @Select("""
            <script>
            select
                kb.id as id,
                kb.id as chunk_id,
                1 as chunk_order,
                kb.title as title,
                kb.content as content,
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 'FILE'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 'WEB_SEARCH'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 'MANUAL_INPUT'
                    when coalesce(cast(kb.source_type as varchar), '') = '0' then 'FILE'
                    when coalesce(cast(kb.source_type as varchar), '') = '1' then 'WEB_SEARCH'
                    when coalesce(cast(kb.source_type as varchar), '') = '2' then 'MANUAL_INPUT'
                    else null
                end as source_type,
                kb.source_path as source_path,
                kb.embedding as embedding,
                kb.created_at as created_at,
                kb.updated_at as updated_at,
                1 - (kb.embedding &lt;=&gt; #{vector}::vector) as vector_score
            from knowledge_base kb
            where kb.embedding is not null
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 0
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 2
                    when coalesce(cast(kb.source_type as varchar), '') ~ '^[0-9]+$' then cast(cast(kb.source_type as varchar) as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by kb.embedding &lt;=&gt; #{vector}::vector
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByVector(@Param("vector") String vector,
                                                          @Param("topK") int topK,
                                                          @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            <script>
            select
                kb.id as id,
                kb.id as chunk_id,
                1 as chunk_order,
                kb.title as title,
                kb.content as content,
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 'FILE'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 'WEB_SEARCH'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 'MANUAL_INPUT'
                    when coalesce(cast(kb.source_type as varchar), '') = '0' then 'FILE'
                    when coalesce(cast(kb.source_type as varchar), '') = '1' then 'WEB_SEARCH'
                    when coalesce(cast(kb.source_type as varchar), '') = '2' then 'MANUAL_INPUT'
                    else null
                end as source_type,
                kb.source_path as source_path,
                kb.embedding as embedding,
                kb.created_at as created_at,
                kb.updated_at as updated_at,
                case
                    when lower(coalesce(kb.title, '')) = lower(#{query})
                      or lower(coalesce(kb.content, '')) = lower(#{query}) then 1.0
                    else 0.0
                end as fts_score
            from knowledge_base kb
            where (
                lower(coalesce(kb.title, '')) = lower(#{query})
                or lower(coalesce(kb.content, '')) = lower(#{query})
            )
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 0
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 2
                    when coalesce(cast(kb.source_type as varchar), '') ~ '^[0-9]+$' then cast(cast(kb.source_type as varchar) as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by kb.updated_at desc
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByExact(@Param("query") String query,
                                                         @Param("topK") int topK,
                                                         @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            <script>
            select
                kb.id as id,
                kb.id as chunk_id,
                1 as chunk_order,
                kb.title as title,
                kb.content as content,
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 'FILE'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 'WEB_SEARCH'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 'MANUAL_INPUT'
                    when coalesce(cast(kb.source_type as varchar), '') = '0' then 'FILE'
                    when coalesce(cast(kb.source_type as varchar), '') = '1' then 'WEB_SEARCH'
                    when coalesce(cast(kb.source_type as varchar), '') = '2' then 'MANUAL_INPUT'
                    else null
                end as source_type,
                kb.source_path as source_path,
                kb.embedding as embedding,
                kb.created_at as created_at,
                kb.updated_at as updated_at,
                ts_rank(
                  to_tsvector('simple', coalesce(kb.title, '') || ' ' || coalesce(kb.content, '')),
                  plainto_tsquery('simple', #{query})
                ) as fts_score
            from knowledge_base kb
            where to_tsvector('simple', coalesce(kb.title, '') || ' ' || coalesce(kb.content, ''))
                  @@ plainto_tsquery('simple', #{query})
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 0
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 2
                    when coalesce(cast(kb.source_type as varchar), '') ~ '^[0-9]+$' then cast(cast(kb.source_type as varchar) as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by fts_score desc, kb.updated_at desc
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByFts(@Param("query") String query,
                                                       @Param("topK") int topK,
                                                       @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            <script>
            select
                kb.id as id,
                kb.id as chunk_id,
                1 as chunk_order,
                kb.title as title,
                kb.content as content,
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 'FILE'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 'WEB_SEARCH'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 'MANUAL_INPUT'
                    when coalesce(cast(kb.source_type as varchar), '') = '0' then 'FILE'
                    when coalesce(cast(kb.source_type as varchar), '') = '1' then 'WEB_SEARCH'
                    when coalesce(cast(kb.source_type as varchar), '') = '2' then 'MANUAL_INPUT'
                    else null
                end as source_type,
                kb.source_path as source_path,
                kb.embedding as embedding,
                kb.created_at as created_at,
                kb.updated_at as updated_at,
                case
                    when coalesce(kb.title, '') ilike concat('%', #{query}, '%') then 1.0
                    when coalesce(kb.content, '') ilike concat('%', #{query}, '%') then 0.8
                    else 0.0
                end as fts_score
            from knowledge_base kb
            where (
                coalesce(kb.title, '') ilike concat('%', #{query}, '%')
                or coalesce(kb.content, '') ilike concat('%', #{query}, '%')
            )
            <if test="sourceTypes != null and sourceTypes.size() > 0">
              and (
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 0
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 1
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 2
                    when coalesce(cast(kb.source_type as varchar), '') ~ '^[0-9]+$' then cast(cast(kb.source_type as varchar) as integer)
                    else null
                end
              ) in
              <foreach collection="sourceTypes" item="sourceType" open="(" separator="," close=")">
                #{sourceType}
              </foreach>
            </if>
            order by fts_score desc, kb.updated_at desc
            limit #{topK}
            </script>
            """)
    List<KnowledgeChunkRecord> searchRagKnowledgeByKeyword(@Param("query") String query,
                                                           @Param("topK") int topK,
                                                           @Param("sourceTypes") List<Integer> sourceTypes);

    @Select("""
            select count(1)
            from knowledge_base kb
            where (#{title} is null or #{title} = '' or kb.title ilike concat('%', #{title}, '%'))
              and (#{content} is null or #{content} = '' or kb.content ilike concat('%', #{content}, '%'))
              and (
                #{sourceType} is null or #{sourceType} = ''
                or upper(cast(kb.source_type as varchar)) = upper(#{sourceType})
                or cast(kb.source_type as varchar) = #{sourceType}
              )
              and (#{sourcePath} is null or #{sourcePath} = '' or kb.source_path ilike concat('%', #{sourcePath}, '%'))
              and (#{startTime} is null or kb.created_at >= #{startTime})
              and (#{endTime} is null or kb.created_at <= #{endTime})
            """)
    Long countByFilters(@Param("title") String title,
                        @Param("content") String content,
                        @Param("sourceType") String sourceType,
                        @Param("sourcePath") String sourcePath,
                        @Param("startTime") LocalDateTime startTime,
                        @Param("endTime") LocalDateTime endTime);

    @Select("""
            select
                kb.id as id,
                kb.id as chunk_id,
                1 as chunk_order,
                kb.title as title,
                kb.content as content,
                case
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'FILE' then 'FILE'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'WEB_SEARCH' then 'WEB_SEARCH'
                    when upper(coalesce(cast(kb.source_type as varchar), '')) = 'MANUAL_INPUT' then 'MANUAL_INPUT'
                    when coalesce(cast(kb.source_type as varchar), '') = '0' then 'FILE'
                    when coalesce(cast(kb.source_type as varchar), '') = '1' then 'WEB_SEARCH'
                    when coalesce(cast(kb.source_type as varchar), '') = '2' then 'MANUAL_INPUT'
                    else null
                end as source_type,
                kb.source_path as source_path,
                kb.embedding as embedding,
                kb.created_at as created_at,
                kb.updated_at as updated_at
            from knowledge_base kb
            where (#{title} is null or #{title} = '' or kb.title ilike concat('%', #{title}, '%'))
              and (#{content} is null or #{content} = '' or kb.content ilike concat('%', #{content}, '%'))
              and (
                #{sourceType} is null or #{sourceType} = ''
                or upper(cast(kb.source_type as varchar)) = upper(#{sourceType})
                or cast(kb.source_type as varchar) = #{sourceType}
              )
              and (#{sourcePath} is null or #{sourcePath} = '' or kb.source_path ilike concat('%', #{sourcePath}, '%'))
              and (#{startTime} is null or kb.created_at >= #{startTime})
              and (#{endTime} is null or kb.created_at <= #{endTime})
            order by kb.created_at desc
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

