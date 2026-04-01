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
              cast(te.episode_id as varchar) as id,
              te.session_id as session_id,
              cast(te.episode_type as varchar) as memory_type,
              coalesce(te.trajectory_summary, te.outcome_summary, te.task_goal, te.title, '') as content,
              cast(greatest(1, least(10, round(coalesce(te.importance_score, 0.5) * 10))) as integer) as weight,
              te.created_at as created_at,
              te.created_at as updated_at,
              case
                when #{queryVector} is null or #{queryVector} = '' or te.embedding is null then 0.0
                else (1 - (te.embedding &lt;=&gt; #{queryVector}::vector))
              end as vector_score
            from task_episode te
            where te.session_id = #{sessionId}
              <if test="memoryTypes != null and memoryTypes.size() > 0">
                and cast(te.episode_type as varchar) in
                <foreach collection="memoryTypes" item="mt" open="(" separator="," close=")">
                    #{mt}
                </foreach>
              </if>
              <if test="startTime != null">
                and te.created_at &gt;= #{startTime}
              </if>
              <if test="endTime != null">
                and te.created_at &lt;= #{endTime}
              </if>
            order by
              case when #{queryVector} is null or #{queryVector} = '' or te.embedding is null then 1 else 0 end,
              case when #{queryVector} is null or #{queryVector} = '' or te.embedding is null then 0 else (1 - (te.embedding &lt;=&gt; #{queryVector}::vector)) end desc,
              te.created_at desc
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
              cast(te.episode_id as varchar) as id,
              te.session_id as session_id,
              cast(te.episode_type as varchar) as memory_type,
              coalesce(te.trajectory_summary, te.outcome_summary, te.task_goal, te.title, '') as content,
              cast(greatest(1, least(10, round(coalesce(te.importance_score, 0.5) * 10))) as integer) as weight,
              te.created_at as created_at,
              te.created_at as updated_at,
              case
                when coalesce(te.trajectory_summary, te.outcome_summary, te.task_goal, te.title, '') ilike concat('%', #{keyword}, '%') then 1.0
                else 0.0
              end as text_match_score
            from task_episode te
            where te.session_id = #{sessionId}
              and #{keyword} is not null
              and #{keyword} != ''
              and coalesce(te.trajectory_summary, te.outcome_summary, te.task_goal, te.title, '') ilike concat('%', #{keyword}, '%')
              <if test="memoryTypes != null and memoryTypes.size() > 0">
                and cast(te.episode_type as varchar) in
                <foreach collection="memoryTypes" item="mt" open="(" separator="," close=")">
                    #{mt}
                </foreach>
              </if>
              <if test="startTime != null">
                and te.created_at &gt;= #{startTime}
              </if>
              <if test="endTime != null">
                and te.created_at &lt;= #{endTime}
              </if>
            order by
              text_match_score desc,
              weight desc,
              te.created_at desc
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
            with preference_union as (
              select
                concat('task:', tsf.fact_id) as id,
                tsf.fact_key as pref_key,
                tsf.fact_value_text as pref_value,
                tsf.description as description,
                tsf.created_at as created_at,
                tsf.updated_at as updated_at,
                tsf.embedding as embedding
              from task_semantic_fact tsf
              where coalesce(tsf.deleted, false) = false
                and tsf.fact_type in ('PREFERENCE', 'PROFILE')
              union all
              select
                concat('rel:', rsf.fact_id) as id,
                rsf.fact_key as pref_key,
                rsf.fact_value_text as pref_value,
                rsf.description as description,
                rsf.created_at as created_at,
                rsf.updated_at as updated_at,
                rsf.embedding as embedding
              from relational_semantic_fact rsf
              where coalesce(rsf.deleted, false) = false
            )
            select
              id,
              pref_key,
              pref_value,
              description,
              created_at,
              updated_at,
              case
                when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0.0
                else (1 - (embedding <=> #{queryVector}::vector))
              end as vector_score
            from preference_union
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              updated_at desc
            limit #{topK}
            </script>
            """)
    List<Map<String, Object>> selectPreferenceByVector(@Param("queryVector") String queryVector,
                                                       @Param("topK") int topK);

    @Select("""
            <script>
            with preference_union as (
              select
                concat('task:', tsf.fact_id) as id,
                tsf.fact_key as pref_key,
                tsf.fact_value_text as pref_value,
                tsf.description as description,
                tsf.created_at as created_at,
                tsf.updated_at as updated_at
              from task_semantic_fact tsf
              where coalesce(tsf.deleted, false) = false
                and tsf.fact_type in ('PREFERENCE', 'PROFILE')
              union all
              select
                concat('rel:', rsf.fact_id) as id,
                rsf.fact_key as pref_key,
                rsf.fact_value_text as pref_value,
                rsf.description as description,
                rsf.created_at as created_at,
                rsf.updated_at as updated_at
              from relational_semantic_fact rsf
              where coalesce(rsf.deleted, false) = false
            )
            select
              id,
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
            from preference_union
            where (
              (#{prefKey} is not null and #{prefKey} != '' and pref_key = #{prefKey})
              or (#{keyword} is not null and #{keyword} != '' and (
                    coalesce(pref_value, '') % #{keyword}
                    or coalesce(description, '') % #{keyword}
                  ))
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
