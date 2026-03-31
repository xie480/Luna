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
              cast(id as varchar) as id,
              cast(memory_type as varchar) as memory_type,
              content,
              coalesce(weight, 1) as weight,
              created_at,
              updated_at,
              case
                when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0.0
                else (1 - (embedding &lt;=&gt; #{queryVector}::vector))
              end as vector_score
            from luna_memory
            where session_id = #{sessionId}
              <if test="memoryTypes != null and memoryTypes.size() > 0">
                and cast(memory_type as varchar) in
                <foreach collection="memoryTypes" item="mt" open="(" separator="," close=")">
                    #{mt}
                </foreach>
              </if>
              <if test="startTime != null">
                and created_at &gt;= #{startTime}
              </if>
              <if test="endTime != null">
                and created_at &lt;= #{endTime}
              </if>
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding &lt;=&gt; #{queryVector}::vector)) end desc,
              updated_at desc
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
              cast(id as varchar) as id,
              cast(memory_type as varchar) as memory_type,
              content,
              coalesce(weight, 1) as weight,
              created_at,
              updated_at,
              case
                when content ilike concat('%', #{keyword}, '%') then 1.0
                else 0.0
              end as text_match_score
            from luna_memory
            where session_id = #{sessionId}
              and #{keyword} is not null
              and #{keyword} != ''
              and content ilike concat('%', #{keyword}, '%')
              <if test="memoryTypes != null and memoryTypes.size() > 0">
                and cast(memory_type as varchar) in
                <foreach collection="memoryTypes" item="mt" open="(" separator="," close=")">
                    #{mt}
                </foreach>
              </if>
              <if test="startTime != null">
                and created_at &gt;= #{startTime}
              </if>
              <if test="endTime != null">
                and created_at &lt;= #{endTime}
              </if>
            order by
              text_match_score desc,
              weight desc,
              updated_at desc
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
            select
              cast(id as varchar) as id,
              pref_key,
              pref_value,
              description,
              created_at,
              updated_at,
              case
                when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0.0
                else (1 - (embedding <=> #{queryVector}::vector))
              end as vector_score
            from user_preference
            where deleted = 0
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              updated_at desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectPreferenceByVector(@Param("queryVector") String queryVector,
                                                       @Param("topK") int topK);

    @Select("""
            <script>
            select
              cast(id as varchar) as id,
              pref_key,
              pref_value,
              description,
              created_at,
              updated_at,
              case when pref_key = #{prefKey} then 1.0 else 0.0 end as key_match_score,
              greatest(
                case when pref_key = #{prefKey} then 1.0 else 0.0 end,
                similarity(coalesce(pref_value, ''), coalesce(#{keyword}, '')),
                similarity(coalesce(description, ''), coalesce(#{keyword}, ''))
              ) as text_match_score
            from user_preference
            where deleted = 0
              and (
                (#{prefKey} is not null and #{prefKey} != '' and pref_key = #{prefKey})
                or (#{keyword} is not null and #{keyword} != '' and (coalesce(pref_value, '') % #{keyword} or coalesce(description, '') % #{keyword}))
              )
            order by
              key_match_score desc,
              text_match_score desc,
              updated_at desc
            limit #{topK}
            </script>
            """)
    List<Map<String, Object>> selectPreferenceByExactOrTrigram(@Param("prefKey") String prefKey,
                                                                @Param("keyword") String keyword,
                                                                @Param("topK") int topK);
}
