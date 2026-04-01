package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface RagMemoryMapper {

    @Select("""
            <script>
            select
              cast(lm.id as varchar) as id,
              lm.session_id as session_id,
              cast(lm.memory_type as varchar) as memory_type,
              coalesce(lm.content, '') as content,
              cast(greatest(1, least(10, coalesce(lm.weight, 1))) as integer) as weight,
              lm.created_at as created_at,
              lm.updated_at as updated_at,
              case
                when #{queryVector} is null or #{queryVector} = '' or lm.embedding is null then 0.0
                else (1 - (lm.embedding &lt;=&gt; #{queryVector}::vector))
              end as vector_score
            from luna_memory lm
            where lm.session_id = #{sessionId}
              <if test="memoryTypes != null and memoryTypes.size() > 0">
                and cast(lm.memory_type as varchar) in
                <foreach collection="memoryTypes" item="mt" open="(" separator="," close=")">
                    #{mt}
                </foreach>
              </if>
              <if test="startTime != null">
                and lm.created_at &gt;= #{startTime}
              </if>
              <if test="endTime != null">
                and lm.created_at &lt;= #{endTime}
              </if>
            order by
              case when #{queryVector} is null or #{queryVector} = '' or lm.embedding is null then 1 else 0 end,
              case when #{queryVector} is null or #{queryVector} = '' or lm.embedding is null then 0 else (1 - (lm.embedding &lt;=&gt; #{queryVector}::vector)) end desc,
              lm.updated_at desc
            limit #{topK}
            </script>
            """)
    List<Map<String, Object>> selectMemoryByVector(@Param("sessionId") String sessionId,
                                                   @Param("queryVector") String queryVector,
                                                   @Param("memoryTypes") List<String> memoryTypes,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime,
                                                   @Param("topK") int topK);

    @Select("""
            <script>
            select
              cast(lm.id as varchar) as id,
              lm.session_id as session_id,
              cast(lm.memory_type as varchar) as memory_type,
              coalesce(lm.content, '') as content,
              cast(greatest(1, least(10, coalesce(lm.weight, 1))) as integer) as weight,
              lm.created_at as created_at,
              lm.updated_at as updated_at,
              case
                when coalesce(lm.content, '') ilike concat('%', #{keyword}, '%') then 1.0
                else 0.0
              end as text_match_score
            from luna_memory lm
            where lm.session_id = #{sessionId}
              and #{keyword} is not null
              and #{keyword} != ''
              and coalesce(lm.content, '') ilike concat('%', #{keyword}, '%')
              <if test="memoryTypes != null and memoryTypes.size() > 0">
                and cast(lm.memory_type as varchar) in
                <foreach collection="memoryTypes" item="mt" open="(" separator="," close=")">
                    #{mt}
                </foreach>
              </if>
              <if test="startTime != null">
                and lm.created_at &gt;= #{startTime}
              </if>
              <if test="endTime != null">
                and lm.created_at &lt;= #{endTime}
              </if>
            order by
              text_match_score desc,
              weight desc,
              lm.updated_at desc
            limit #{topK}
            </script>
            """)
    List<Map<String, Object>> selectMemoryByKeyword(@Param("sessionId") String sessionId,
                                                    @Param("keyword") String keyword,
                                                    @Param("memoryTypes") List<String> memoryTypes,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime,
                                                    @Param("topK") int topK);

    @Select("""
            <script>
            select
              cast(up.id as varchar) as id,
              up.pref_key as pref_key,
              up.pref_value as pref_value,
              up.description as description,
              up.created_at as created_at,
              up.updated_at as updated_at,
              case
                when #{queryVector} is null or #{queryVector} = '' or up.embedding is null then 0.0
                else (1 - (up.embedding &lt;=&gt; #{queryVector}::vector))
              end as vector_score
            from user_preference up
            where coalesce(up.deleted, 0) = 0
            order by
              case when #{queryVector} is null or #{queryVector} = '' or up.embedding is null then 1 else 0 end,
              case when #{queryVector} is null or #{queryVector} = '' or up.embedding is null then 0 else (1 - (up.embedding &lt;=&gt; #{queryVector}::vector)) end desc,
              up.updated_at desc
            limit #{topK}
            </script>
            """)
    List<Map<String, Object>> selectPreferenceByVector(@Param("queryVector") String queryVector,
                                                       @Param("topK") int topK);

    @Select("""
            <script>
            select
              cast(up.id as varchar) as id,
              up.pref_key as pref_key,
              up.pref_value as pref_value,
              up.description as description,
              up.created_at as created_at,
              up.updated_at as updated_at,
              case when up.pref_key = #{prefKey} then 1.0 else 0.0 end as key_match_score,
              greatest(
                case when up.pref_key = #{prefKey} then 1.0 else 0.0 end,
                similarity(coalesce(up.pref_value, ''), coalesce(#{keyword}, '')),
                similarity(coalesce(up.description, ''), coalesce(#{keyword}, ''))
              ) as text_match_score
            from user_preference up
            where coalesce(up.deleted, 0) = 0
              and (
                (#{prefKey} is not null and #{prefKey} != '' and up.pref_key = #{prefKey})
                or (#{keyword} is not null and #{keyword} != '' and (
                      coalesce(up.pref_value, '') % #{keyword}
                      or coalesce(up.description, '') % #{keyword}
                    ))
              )
            order by
              key_match_score desc,
              text_match_score desc,
              up.updated_at desc
            limit #{topK}
            </script>
            """)
    List<Map<String, Object>> selectPreferenceByExactOrTrigram(@Param("prefKey") String prefKey,
                                                                @Param("keyword") String keyword,
                                                                @Param("topK") int topK);
}
