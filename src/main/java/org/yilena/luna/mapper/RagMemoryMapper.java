package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RagMemoryMapper {

    @Select("""
            select cast(fact_id as varchar) as id, 'task_fact' as memory_type, fact_value_text as content, confidence_score as score, source_ref as ref
            from task_semantic_fact
            where deleted = false
              and principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1)
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              updated_at desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectTaskFactMemory(@Param("sessionId") String sessionId,
                                                   @Param("queryVector") String queryVector,
                                                   @Param("topK") int topK);

    @Select("""
            select cast(episode_id as varchar) as id, 'task_episode' as memory_type, coalesce(outcome_summary, trajectory_summary) as content, importance_score as score, session_id as ref
            from task_episode
            where session_id = #{sessionId}
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              created_at desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectTaskEpisodeMemory(@Param("sessionId") String sessionId,
                                                      @Param("queryVector") String queryVector,
                                                      @Param("topK") int topK);

    @Select("""
            select cast(episode_id as varchar) as id, 'relational_episode' as memory_type, summary as content, response_effectiveness as score, session_id as ref
            from relational_episode
            where session_id = #{sessionId}
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              created_at desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectRelationalEpisodeMemory(@Param("sessionId") String sessionId,
                                                            @Param("queryVector") String queryVector,
                                                            @Param("topK") int topK);

    @Select("""
            select cast(procedure_id as varchar) as id, 'task_procedure' as memory_type, coalesce(description, name) as content, confidence_score as score, 'task_procedure_pattern' as ref
            from task_procedure_pattern
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              confidence_score desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectTaskProcedureMemory(@Param("queryVector") String queryVector, @Param("topK") int topK);

    @Select("""
            select cast(procedure_id as varchar) as id, 'relational_procedure' as memory_type, coalesce(description, name) as content, confidence_score as score, 'relational_procedure_pattern' as ref
            from relational_procedure_pattern
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              confidence_score desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectRelationalProcedureMemory(@Param("queryVector") String queryVector, @Param("topK") int topK);

    @Select("""
            select cast(fact_id as varchar) as id, fact_key as pref_key, fact_value_text as pref_value, description
            from relational_semantic_fact
            where deleted = false
              and principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1)
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              updated_at desc
            limit #{topK}
            """)
    List<Map<String, Object>> selectPreferenceMemory(@Param("sessionId") String sessionId,
                                                     @Param("queryVector") String queryVector,
                                                     @Param("topK") int topK);
}
