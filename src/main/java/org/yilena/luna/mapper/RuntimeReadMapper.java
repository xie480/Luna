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
            order by created_at asc
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
            select slot_name, slot_type, slot_value_json, priority, freshness_score, source_type, source_ref, updated_at
            from task_working_memory_slot
            where twm_id = (
                select twm_id
                from task_working_memory
                where session_id = #{sessionId}
                limit 1
            )
            order by priority desc, updated_at desc
            limit 20
            """)
    List<Map<String, Object>> selectTaskWorkingSlots(@Param("sessionId") String sessionId);

    @Select("""
            select event_id, session_id, message_ref, tool_trace_ref, signal_json, created_at, ttl_at
            from task_perceptual_buffer
            where session_id = #{sessionId}
              and ttl_at > current_timestamp
            order by created_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> selectTaskPerceptualBuffer(@Param("sessionId") String sessionId, @Param("limit") int limit);

    @Select("""
            select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, updated_at
            from task_semantic_fact
            where deleted = false
              and (
                    scope_type in ('GLOBAL','SESSION')
                 or principal_id = (select principal_id from agent_session where session_id = #{sessionId} limit 1)
              )
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              updated_at desc
            limit 20
            """)
    List<Map<String, Object>> selectTaskSemanticFacts(@Param("sessionId") String sessionId, @Param("queryVector") String queryVector);

    @Select("""
            select episode_id, episode_type, title, trajectory_summary, lessons_learned, created_at
            from task_episode
            where session_id = #{sessionId}
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              created_at desc
            limit 8
            """)
    List<Map<String, Object>> selectTaskEpisodes(@Param("sessionId") String sessionId, @Param("queryVector") String queryVector);

    @Select("""
            select tes.episode_id, tes.step_order, tes.step_type, tes.title, tes.content_text, tes.payload_json, tes.created_at
            from task_episode_step tes
            join task_episode te on te.episode_id = tes.episode_id
            where te.session_id = #{sessionId}
            order by tes.created_at desc, tes.step_order desc
            limit 20
            """)
    List<Map<String, Object>> selectTaskEpisodeSteps(@Param("sessionId") String sessionId);

    @Select("""
            select procedure_id, procedure_type, name, description, confidence_score, usage_count
            from task_procedure_pattern
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              confidence_score desc,
              usage_count desc
            limit 8
            """)
    List<Map<String, Object>> selectTaskProcedures(@Param("queryVector") String queryVector);

    @Select("""
            select kc.chunk_id, kd.title, kc.chunk_text, kc.chunk_summary, kc.created_at
            from knowledge_chunk kc
            join knowledge_document kd on kd.doc_id = kc.doc_id
            order by
              case when #{queryVector} is null or #{queryVector} = '' or kc.embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or kc.embedding is null then 0 else (1 - (kc.embedding <=> #{queryVector}::vector)) end desc,
              kc.created_at desc
            limit 10
            """)
    List<Map<String, Object>> selectKnowledgeChunks(@Param("queryVector") String queryVector);

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
            order by
              case when #{queryVector} is null or #{queryVector} = '' or rsf.embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or rsf.embedding is null then 0 else (1 - (rsf.embedding <=> #{queryVector}::vector)) end desc,
              rsf.updated_at desc
            limit 20
            """)
    List<Map<String, Object>> selectRelationalSemanticFacts(@Param("sessionId") String sessionId, @Param("queryVector") String queryVector);

    @Select("""
            select event_id, session_id, message_ref, emotion_signal_json, boundary_signal_json, created_at, ttl_at
            from relational_perceptual_buffer
            where session_id = #{sessionId}
              and ttl_at > current_timestamp
            order by created_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> selectRelationalPerceptualBuffer(@Param("sessionId") String sessionId, @Param("limit") int limit);

    @Select("""
            select episode_id, episode_type, title, summary, support_style_used, interaction_quality, created_at
            from relational_episode
            where session_id = #{sessionId}
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              created_at desc
            limit 8
            """)
    List<Map<String, Object>> selectRelationalEpisodes(@Param("sessionId") String sessionId, @Param("queryVector") String queryVector);

    @Select("""
            select procedure_id, procedure_type, name, description, confidence_score, usage_count
            from relational_procedure_pattern
            order by
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 1 else 0 end asc,
              case when #{queryVector} is null or #{queryVector} = '' or embedding is null then 0 else (1 - (embedding <=> #{queryVector}::vector)) end desc,
              confidence_score desc,
              usage_count desc
            limit 8
            """)
    List<Map<String, Object>> selectRelationalProcedures(@Param("queryVector") String queryVector);

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

