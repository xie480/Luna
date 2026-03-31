package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RuntimeReadMapper {

    @Select("""
            select session_id, session_type, task_state, relational_state, current_plan_id, current_goal,
                   last_user_message_at, last_agent_message_at, metadata_json
            from agent_session
            where session_id = #{sessionId}
            limit 1
            """)
    Map<String, Object> selectRuntimeSession(@Param("sessionId") String sessionId);

    @Select("""
            select message_id, role, message_type, content_text, trace_id, created_at
            from conversation_message
            where session_id = #{sessionId}
            order by created_at desc
            limit 12
            """)
    List<Map<String, Object>> selectRuntimeRecentMessages(@Param("sessionId") String sessionId);

    @Select("""
            select tool_name, call_status, normalized_output, error_message, created_at
            from tool_execution_trace
            where session_id = #{sessionId}
            order by created_at desc
            limit 8
            """)
    List<Map<String, Object>> selectRuntimeToolResults(@Param("sessionId") String sessionId);

    @Select("""
            select id, plan_id, node_id, created_at
            from plan_context_snapshot
            where session_id = #{sessionId}
            order by created_at desc
            limit 3
            """)
    List<Map<String, Object>> selectRuntimeContextSnapshots(@Param("sessionId") String sessionId);

    @Select("select * from task_working_memory where session_id = #{sessionId} limit 1")
    Map<String, Object> selectTaskWorkingMemory(@Param("sessionId") String sessionId);

    @Select("""
            select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, updated_at
            from task_semantic_fact
            where deleted = false
              and (
                    scope_type in ('GLOBAL','SESSION')
                 or principal_id = (
                    select principal_id from agent_session where session_id = #{sessionId} limit 1
                 )
              )
            order by updated_at desc
            limit 20
            """)
    List<Map<String, Object>> selectTaskSemanticFacts(@Param("sessionId") String sessionId);

    @Select("""
            select episode_id, episode_type, title, trajectory_summary, lessons_learned, created_at
            from task_episode
            where session_id = #{sessionId}
            order by created_at desc
            limit 8
            """)
    List<Map<String, Object>> selectTaskEpisodes(@Param("sessionId") String sessionId);

    @Select("""
            select procedure_id, procedure_type, name, description, confidence_score, usage_count
            from task_procedure_pattern
            order by confidence_score desc, usage_count desc
            limit 8
            """)
    List<Map<String, Object>> selectTaskProcedures();

    @Select("""
            select kc.chunk_id, kd.title, kc.chunk_text, kc.chunk_summary, kc.created_at
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            order by kc.created_at desc
            limit 10
            """)
    List<Map<String, Object>> selectKnowledgeChunks();

    @Select("""
            select id, plan_id, node_id, context_package_json, created_at
            from plan_context_snapshot
            where session_id = #{sessionId}
            order by created_at desc
            limit 1
            """)
    Map<String, Object> selectLatestPlanContext(@Param("sessionId") String sessionId);

    @Select("select * from relational_working_memory where session_id = #{sessionId} limit 1")
    Map<String, Object> selectRelationalWorkingMemory(@Param("sessionId") String sessionId);

    @Select("""
            select rp.*
            from relational_profile rp
            join agent_session s on s.principal_id = rp.principal_id
            where s.session_id = #{sessionId}
            limit 1
            """)
    Map<String, Object> selectRelationalProfile(@Param("sessionId") String sessionId);

    @Select("""
            select rsf.fact_id, rsf.fact_type, rsf.fact_key, rsf.fact_value_text, rsf.description, rsf.confidence_score, rsf.updated_at
            from relational_semantic_fact rsf
            join agent_session s on (s.principal_id = rsf.principal_id or rsf.principal_id is null)
            where s.session_id = #{sessionId} and rsf.deleted = false
            order by rsf.updated_at desc
            limit 20
            """)
    List<Map<String, Object>> selectRelationalSemanticFacts(@Param("sessionId") String sessionId);

    @Select("""
            select episode_id, episode_type, title, summary, support_style_used, interaction_quality, created_at
            from relational_episode
            where session_id = #{sessionId}
            order by created_at desc
            limit 8
            """)
    List<Map<String, Object>> selectRelationalEpisodes(@Param("sessionId") String sessionId);

    @Select("""
            select procedure_id, procedure_type, name, description, confidence_score, usage_count
            from relational_procedure_pattern
            order by confidence_score desc, usage_count desc
            limit 8
            """)
    List<Map<String, Object>> selectRelationalProcedures();

    @Select("""
            select eb.*
            from emotional_baseline eb
            join agent_session s on s.principal_id = eb.principal_id
            where s.session_id = #{sessionId}
            limit 1
            """)
    Map<String, Object> selectEmotionalBaseline(@Param("sessionId") String sessionId);

    @Select("""
            select rbr.id, rbr.rule_type, rbr.rule_key, rbr.rule_value, rbr.confidence_score, rbr.updated_at
            from relational_boundary_rule rbr
            join agent_session s on s.principal_id = rbr.principal_id
            where s.session_id = #{sessionId}
            order by rbr.updated_at desc
            limit 10
            """)
    List<Map<String, Object>> selectBoundaryRules(@Param("sessionId") String sessionId);
}
