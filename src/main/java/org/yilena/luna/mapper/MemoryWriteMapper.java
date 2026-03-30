package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MemoryWriteMapper {

    @Insert("""
            insert into conversation_message(session_id, role, message_type, content_text, created_at)
            values (#{sessionId}, #{role}, 'TEXT', #{content}, current_timestamp)
            """)
    int insertMessage(@Param("sessionId") String sessionId, @Param("role") String role, @Param("content") String content);

    @Update("""
            update agent_session
            set task_state = #{taskState},
                relational_state = #{relationalState},
                last_agent_message_at = current_timestamp,
                updated_at = current_timestamp
            where session_id = #{sessionId}
            """)
    int updateSessionState(@Param("sessionId") String sessionId,
                           @Param("taskState") String taskState,
                           @Param("relationalState") String relationalState);

    @Update("""
            insert into task_working_memory(session_id, goal_raw, goal_refined, intent_json, version, updated_at)
            values (#{sessionId}, #{goalRaw}, #{goalRefined}, jsonb_build_object('source','memory_write_pipeline'), 1, current_timestamp)
            on conflict (session_id)
            do update set goal_raw = excluded.goal_raw,
                          goal_refined = excluded.goal_refined,
                          version = task_working_memory.version + 1,
                          updated_at = current_timestamp
            """)
    int upsertTaskWorking(@Param("sessionId") String sessionId,
                          @Param("goalRaw") String goalRaw,
                          @Param("goalRefined") String goalRefined);

    @Update("""
            insert into relational_working_memory(session_id, current_relational_state, desired_tone, updated_at)
            values (#{sessionId}, #{relationalState}, #{desiredTone}, current_timestamp)
            on conflict (session_id)
            do update set current_relational_state = excluded.current_relational_state,
                          desired_tone = excluded.desired_tone,
                          updated_at = current_timestamp
            """)
    int upsertRelationalWorking(@Param("sessionId") String sessionId,
                                @Param("relationalState") String relationalState,
                                @Param("desiredTone") String desiredTone);

    @Insert("""
            insert into task_semantic_fact(principal_id, scope_type, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at)
            values (cast(abs(hashtext(#{sessionId})) as bigint), 'SESSION', #{factType}, #{factKey}, #{factValue}, 0.7, 0.7, #{sourceType}, #{sourceRef}, false, current_timestamp, current_timestamp)
            """)
    int insertTaskSemanticFact(@Param("sessionId") String sessionId, @Param("factType") String factType, @Param("factKey") String factKey,
                               @Param("factValue") String factValue, @Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef);

    @Insert("""
            insert into relational_semantic_fact(principal_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at)
            values (cast(abs(hashtext(#{sessionId})) as bigint), #{factType}, #{factKey}, #{factValue}, 0.75, 0.75, #{sourceType}, #{sourceRef}, false, current_timestamp, current_timestamp)
            """)
    int insertRelationalSemanticFact(@Param("sessionId") String sessionId, @Param("factType") String factType, @Param("factKey") String factKey,
                                     @Param("factValue") String factValue, @Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef);

    @Insert("""
            insert into task_episode(principal_id, session_id, plan_id, episode_type, title, task_goal, trajectory_summary, outcome_summary, outcome_status, lessons_learned, importance_score, reusability_score, created_at)
            select principal_id, #{sessionId}, current_plan_id, #{episodeType}, #{title}, current_goal, #{trajectorySummary}, #{outcomeSummary}, #{outcomeStatus}, #{lessonsLearned}, 0.65, 0.60, current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertTaskEpisode(@Param("sessionId") String sessionId, @Param("episodeType") String episodeType, @Param("title") String title,
                          @Param("trajectorySummary") String trajectorySummary, @Param("outcomeSummary") String outcomeSummary,
                          @Param("outcomeStatus") String outcomeStatus, @Param("lessonsLearned") String lessonsLearned);

    @Insert("""
            insert into relational_episode(principal_id, session_id, episode_type, title, summary, emotion_before, emotion_after, support_style_used, interaction_quality, response_effectiveness, created_at)
            select principal_id, #{sessionId}, #{episodeType}, #{title}, #{summary}, #{emotionBefore}, #{emotionAfter}, #{supportStyleUsed}, 0.70, 0.70, current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertRelationalEpisode(@Param("sessionId") String sessionId, @Param("episodeType") String episodeType, @Param("title") String title,
                                @Param("summary") String summary, @Param("emotionBefore") String emotionBefore,
                                @Param("emotionAfter") String emotionAfter, @Param("supportStyleUsed") String supportStyleUsed);

    @Update("update task_procedure_pattern set usage_count = usage_count + 1, success_count = success_count + #{successInc}, fail_count = fail_count + #{failInc}, updated_at = current_timestamp where name = 'default_task_execution'")
    int updateTaskExecutionProcedureStats(@Param("successInc") int successInc, @Param("failInc") int failInc);

    @Update("update task_procedure_pattern set usage_count = usage_count + 1, fail_count = fail_count + 1, updated_at = current_timestamp where name = 'default_failure_recovery'")
    int incrementTaskFailureRecovery();

    @Update("update relational_procedure_pattern set usage_count = usage_count + 1, success_count = success_count + #{successInc}, fail_count = fail_count + #{failInc}, updated_at = current_timestamp where name = 'default_relational_support'")
    int updateRelationalSupportProcedureStats(@Param("successInc") int successInc, @Param("failInc") int failInc);

    @Update("update relational_procedure_pattern set usage_count = usage_count + 1, fail_count = fail_count + 1, updated_at = current_timestamp where name = 'default_relation_repair'")
    int incrementRelationalRepair();

    @Insert("""
            insert into task_reflection_record(plan_id, node_id, reflection_type, trigger_reason, observation, root_cause, proposed_fix, extracted_pattern_json, quality_score, created_at)
            select current_plan_id, null, #{reflectionType}, #{triggerReason}, #{observation}, #{rootCause}, #{proposedFix}, jsonb_build_object('source','memory_write_pipeline'), 0.65, current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertTaskReflection(@Param("sessionId") String sessionId, @Param("reflectionType") String reflectionType, @Param("triggerReason") String triggerReason,
                             @Param("observation") String observation, @Param("rootCause") String rootCause, @Param("proposedFix") String proposedFix);

    @Insert("""
            insert into relational_reflection_record(session_id, reflection_type, trigger_reason, observation, root_cause, proposed_fix, extracted_pattern_json, quality_score, created_at)
            values (#{sessionId}, 'MISALIGNMENT', 'user_signal', #{observation}, #{rootCause}, #{proposedFix}, jsonb_build_object('source','memory_write_pipeline'), 0.68, current_timestamp)
            """)
    int insertRelationalReflection(@Param("sessionId") String sessionId, @Param("observation") String observation,
                                   @Param("rootCause") String rootCause, @Param("proposedFix") String proposedFix);

    @Update("""
            insert into task_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at)
            select 'VALIDATION', 'default_task_execution', 'default execution quality baseline', jsonb_build_object('trigger','task_turn'), jsonb_build_array('understand','execute_or_plan','validate'), 'ONLINE_RUNTIME', 0.60, 0, 0, 0, current_timestamp, current_timestamp
            where not exists (select 1 from task_procedure_pattern where name = 'default_task_execution')
            """)
    int ensureTaskExecutionProcedure();

    @Update("""
            insert into task_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at)
            select 'RECOVERY', 'default_failure_recovery', 'fallback recovery after failed turn', jsonb_build_object('trigger','task_failed'), jsonb_build_array('analyze_root_cause','replan','validate'), 'ONLINE_REFLECTION', 0.62, 0, 0, 0, current_timestamp, current_timestamp
            where not exists (select 1 from task_procedure_pattern where name = 'default_failure_recovery')
            """)
    int ensureTaskRecoveryProcedure();

    @Update("""
            insert into relational_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at)
            select 'COMFORT_PATTERN', 'default_relational_support', 'default relational support baseline', jsonb_build_object('trigger','relational_turn'), jsonb_build_array('attune','respond','confirm'), 'ONLINE_RUNTIME', 0.60, 0, 0, 0, current_timestamp, current_timestamp
            where not exists (select 1 from relational_procedure_pattern where name = 'default_relational_support')
            """)
    int ensureRelationalSupportProcedure();

    @Update("""
            insert into relational_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at)
            select 'REPAIR_PATTERN', 'default_relation_repair', 'repair after user discomfort', jsonb_build_object('trigger','relation_misalignment'), jsonb_build_array('acknowledge','align','confirm'), 'ONLINE_REFLECTION', 0.66, 0, 0, 0, current_timestamp, current_timestamp
            where not exists (select 1 from relational_procedure_pattern where name = 'default_relation_repair')
            """)
    int ensureRelationalRepairProcedure();

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'TASK','WORKING','task_working_memory', cast(twm_id as varchar), cast(abs(hashtext(#{sessionId})) as bigint), 'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp
            from task_working_memory
            where session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshTaskWorkingRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','WORKING','relational_working_memory', cast(rwm_id as varchar), cast(abs(hashtext(#{sessionId})) as bigint), 'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp
            from relational_working_memory
            where session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshRelationalWorkingRegistry(@Param("sessionId") String sessionId);
}
