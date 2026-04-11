package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.KnowledgeChunkRecord;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
/**
 * 知识库 Mapper，负责执行知识文档筛选查询、RAG 检索以及知识文档和切片写入。
 */
public interface KnowledgeBaseMapper {

    @Select("""
            select id, title, content, source_type, source_path, created_at, updated_at
            from knowledge_base
            where (coalesce(#{keyword}, '') = '' or title ilike concat('%', #{keyword}, '%') or content ilike concat('%', #{keyword}, '%'))
            order by updated_at desc
            limit #{limit}
            """)
    /**
     * 按关键字检索基础知识资源列表。
     */
    List<Map<String, Object>> selectResourceKnowledgeByKeyword(@Param("keyword") String keyword,
                                                               @Param("limit") int limit);

    @Select("""
            select id, title, content, source_type, source_path, created_at, updated_at
            from knowledge_base
            where id = #{id}
            limit 1
            """)
    /**
     * 按主键查询单条知识资源。
     */
    List<Map<String, Object>> selectResourceKnowledgeById(@Param("id") Long id);

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
    /**
     * 按向量相似度搜索知识记录。
     */
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
    /**
     * 在指定来源范围内执行 RAG 向量检索。
     */
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
    /**
     * 按完全匹配方式检索 RAG 知识。
     */
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
    /**
     * 按全文检索分数搜索 RAG 知识。
     */
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
    /**
     * 按关键字模糊匹配搜索 RAG 知识。
     */
    List<KnowledgeChunkRecord> searchRagKnowledgeByKeyword(@Param("query") String query,
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
                greatest(
                    similarity(coalesce(kb.title, ''), coalesce(#{query}, '')),
                    similarity(coalesce(kb.content, ''), coalesce(#{query}, ''))
                ) as fts_score
            from knowledge_base kb
            where #{query} is not null and #{query} != ''
              and (
                coalesce(kb.title, '') % #{query}
                or coalesce(kb.content, '') % #{query}
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
    /**
     * 按 trigram 相似度搜索 RAG 知识。
     */
    List<KnowledgeChunkRecord> searchRagKnowledgeByTrigram(@Param("query") String query,
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
    /**
     * 按组合筛选条件统计知识记录总数。
     */
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
    /**
     * 按组合筛选条件分页查询知识记录。
     */
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
    /**
     * 插入知识文档主记录并返回文档主键。
     */
    Long insertKnowledgeDocument(@Param("title") String title,
                                 @Param("sourceType") String sourceType,
                                 @Param("sourcePath") String sourcePath);

    @Insert("""
            insert into knowledge_chunk(doc_id, chunk_order, chunk_text, chunk_summary, keywords_json, embedding, metadata_json, created_at)
            values (#{docId}, #{chunkOrder}, #{chunkText}, #{chunkSummary}, cast(#{keywordsJson} as jsonb), #{embedding}::vector,
                    jsonb_build_object('source','knowledge_mq'), current_timestamp)
            """)
    /**
     * 插入知识切片记录。
     */
    int insertKnowledgeChunk(@Param("docId") Long docId,
                             @Param("chunkOrder") int chunkOrder,
                             @Param("chunkText") String chunkText,
                             @Param("chunkSummary") String chunkSummary,
                             @Param("keywordsJson") String keywordsJson,
                             @Param("embedding") String embedding);
}

