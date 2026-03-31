package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ToolMemoryMapper {

    @Update("""
            insert into task_working_memory(session_id, goal_raw, goal_refined, version, updated_at)
            values (#{sessionId}, #{content}, #{content}, 1, current_timestamp)
            on conflict (session_id)
            do update set goal_raw = excluded.goal_raw, goal_refined = excluded.goal_refined,
                          version = task_working_memory.version + 1, updated_at = current_timestamp
            """)
    int upsertTaskWorkingMemory(@Param("sessionId") String sessionId, @Param("content") String content);

    @Update("""
            insert into relational_working_memory(session_id, current_relational_state, interaction_goal, desired_tone, updated_at)
            values (#{sessionId}, 'LIGHT_CHAT', #{content}, 'clear_and_friendly', current_timestamp)
            on conflict (session_id)
            do update set interaction_goal = excluded.interaction_goal, updated_at = current_timestamp
            """)
    int upsertRelationalWorkingMemory(@Param("sessionId") String sessionId, @Param("content") String content);

    @Insert("""
            insert into task_semantic_fact(principal_id, scope_type, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, embedding, deleted, created_at, updated_at)
            select s.principal_id, 'SESSION', #{factType}, #{factKey}, #{content}, 0.8, 0.7, 'TOOL_MANAGE_MEMORY', #{sessionId}, #{embedding}::vector, false, current_timestamp, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            """)
    int insertTaskSemanticFact(@Param("sessionId") String sessionId, @Param("factType") String factType,
                               @Param("factKey") String factKey, @Param("content") String content,
                               @Param("embedding") String embedding);

    @Insert("""
            insert into relational_semantic_fact(principal_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, embedding, deleted, created_at, updated_at)
            select s.principal_id, #{factType}, #{factKey}, #{content}, 0.8, 0.7, 'TOOL_MANAGE_MEMORY', #{sessionId}, #{embedding}::vector, false, current_timestamp, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            """)
    int insertRelationalSemanticFact(@Param("sessionId") String sessionId, @Param("factType") String factType,
                                     @Param("factKey") String factKey, @Param("content") String content,
                                     @Param("embedding") String embedding);

    @Insert("""
            insert into task_episode(principal_id, session_id, episode_type, title, trajectory_summary, importance_score, reusability_score, embedding, created_at)
            select s.principal_id, #{sessionId}, 'PARTIAL', left(#{content}, 120), #{content}, 0.6, 0.6, #{embedding}::vector, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            """)
    int insertTaskEpisode(@Param("sessionId") String sessionId, @Param("content") String content, @Param("embedding") String embedding);

    @Insert("""
            insert into relational_episode(principal_id, session_id, episode_type, title, summary, interaction_quality, response_effectiveness, embedding, created_at)
            select s.principal_id, #{sessionId}, 'BONDING', left(#{content}, 120), #{content}, 0.7, 0.7, #{embedding}::vector, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            """)
    int insertRelationalEpisode(@Param("sessionId") String sessionId, @Param("content") String content, @Param("embedding") String embedding);

    @Select("select * from task_working_memory where session_id = #{sessionId}")
    List<Map<String, Object>> queryTaskWorkingMemory(@Param("sessionId") String sessionId);

    @Select("select * from relational_working_memory where session_id = #{sessionId}")
    List<Map<String, Object>> queryRelationalWorkingMemory(@Param("sessionId") String sessionId);

    @Select("""
            select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, created_at, updated_at
            from task_semantic_fact
            where (principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1) or principal_id is null) and deleted = false
            order by updated_at desc
            limit 50
            """)
    List<Map<String, Object>> queryTaskSemanticFacts(@Param("sessionId") String sessionId);

    @Select("""
            select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, created_at, updated_at
            from relational_semantic_fact
            where (principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1) or principal_id is null) and deleted = false
            order by updated_at desc
            limit 50
            """)
    List<Map<String, Object>> queryRelationalSemanticFacts(@Param("sessionId") String sessionId);

    @Select("select * from task_episode where session_id = #{sessionId} order by created_at desc limit 30")
    List<Map<String, Object>> queryTaskEpisodes(@Param("sessionId") String sessionId);

    @Select("select * from relational_episode where session_id = #{sessionId} order by created_at desc limit 30")
    List<Map<String, Object>> queryRelationalEpisodes(@Param("sessionId") String sessionId);

    @Delete("delete from task_working_memory where twm_id = #{id}")
    int deleteTaskWorkingMemory(@Param("id") Long id);

    @Delete("delete from relational_working_memory where rwm_id = #{id}")
    int deleteRelationalWorkingMemory(@Param("id") Long id);

    @Delete("delete from task_semantic_fact where fact_id = #{id}")
    int deleteTaskSemanticFact(@Param("id") Long id);

    @Delete("delete from relational_semantic_fact where fact_id = #{id}")
    int deleteRelationalSemanticFact(@Param("id") Long id);

    @Delete("delete from task_episode where episode_id = #{id}")
    int deleteTaskEpisode(@Param("id") Long id);

    @Delete("delete from relational_episode where episode_id = #{id}")
    int deleteRelationalEpisode(@Param("id") Long id);

    @Update("update task_semantic_fact set deleted = true, updated_at = current_timestamp where fact_id = #{id}")
    int softDeleteTaskSemanticFact(@Param("id") Long id);

    @Update("update relational_semantic_fact set deleted = true, updated_at = current_timestamp where fact_id = #{id}")
    int softDeleteRelationalSemanticFact(@Param("id") Long id);
}
