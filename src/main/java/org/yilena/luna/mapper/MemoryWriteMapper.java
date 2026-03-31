package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
                current_plan_id = coalesce(
                    agent_session.current_plan_id,
                    (select plan_id from task_working_memory where session_id = #{sessionId} order by updated_at desc limit 1)
                ),
                last_agent_message_at = current_timestamp,
                updated_at = current_timestamp
            where session_id = #{sessionId}
            """)
    int updateSessionState(@Param("sessionId") String sessionId,
                           @Param("taskState") String taskState,
                           @Param("relationalState") String relationalState);

    @Update("""
            insert into task_working_memory(
                session_id, principal_id, plan_id, goal_raw, goal_refined, intent_json,
                constraints_json, success_criteria_json, assumptions_json, key_entities_json, key_facts_json,
                unresolved_questions_json, risks_json, active_phase_id, active_node_id, recent_tool_outputs_json,
                local_scratchpad, version, updated_at
            )
            select
                s.session_id, s.principal_id, s.current_plan_id, #{goalRaw}, #{goalRefined}, cast(#{intentJson} as jsonb),
                cast(#{constraintsJson} as jsonb), cast(#{successCriteriaJson} as jsonb), cast(#{assumptionsJson} as jsonb),
                cast(#{keyEntitiesJson} as jsonb), cast(#{keyFactsJson} as jsonb),
                cast(#{unresolvedQuestionsJson} as jsonb), cast(#{risksJson} as jsonb),
                #{activePhaseId}, #{activeNodeId}, cast(#{recentToolOutputsJson} as jsonb),
                #{localScratchpad}, 1, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            on conflict (session_id)
            do update set
                principal_id = excluded.principal_id,
                plan_id = excluded.plan_id,
                goal_raw = excluded.goal_raw,
                goal_refined = excluded.goal_refined,
                intent_json = excluded.intent_json,
                constraints_json = excluded.constraints_json,
                success_criteria_json = excluded.success_criteria_json,
                assumptions_json = excluded.assumptions_json,
                key_entities_json = excluded.key_entities_json,
                key_facts_json = excluded.key_facts_json,
                unresolved_questions_json = excluded.unresolved_questions_json,
                risks_json = excluded.risks_json,
                active_phase_id = excluded.active_phase_id,
                active_node_id = excluded.active_node_id,
                recent_tool_outputs_json = excluded.recent_tool_outputs_json,
                local_scratchpad = excluded.local_scratchpad,
                version = task_working_memory.version + 1,
                updated_at = current_timestamp
            """)
    int upsertTaskWorking(@Param("sessionId") String sessionId,
                          @Param("goalRaw") String goalRaw,
                          @Param("goalRefined") String goalRefined,
                          @Param("intentJson") String intentJson,
                          @Param("constraintsJson") String constraintsJson,
                          @Param("successCriteriaJson") String successCriteriaJson,
                          @Param("assumptionsJson") String assumptionsJson,
                          @Param("keyEntitiesJson") String keyEntitiesJson,
                          @Param("keyFactsJson") String keyFactsJson,
                          @Param("unresolvedQuestionsJson") String unresolvedQuestionsJson,
                          @Param("risksJson") String risksJson,
                          @Param("activePhaseId") Long activePhaseId,
                          @Param("activeNodeId") Long activeNodeId,
                          @Param("recentToolOutputsJson") String recentToolOutputsJson,
                          @Param("localScratchpad") String localScratchpad);

    @Update("""
            insert into task_working_memory_slot(twm_id, slot_name, slot_type, slot_value_json, priority, freshness_score, source_type, source_ref, updated_at)
            select twm.twm_id, #{slotName}, #{slotType}, cast(#{slotValueJson} as jsonb), #{priority}, 1.0, #{sourceType}, #{sourceRef}, current_timestamp
            from task_working_memory twm
            where twm.session_id = #{sessionId}
            on conflict (twm_id, slot_name)
            do update set
                slot_type = excluded.slot_type,
                slot_value_json = excluded.slot_value_json,
                priority = excluded.priority,
                freshness_score = excluded.freshness_score,
                source_type = excluded.source_type,
                source_ref = excluded.source_ref,
                updated_at = current_timestamp
            """)
    int upsertTaskWorkingSlot(@Param("sessionId") String sessionId,
                              @Param("slotName") String slotName,
                              @Param("slotType") String slotType,
                              @Param("slotValueJson") String slotValueJson,
                              @Param("priority") int priority,
                              @Param("sourceType") String sourceType,
                              @Param("sourceRef") String sourceRef);

    @Update("""
            insert into relational_working_memory(
                session_id, principal_id, current_relational_state, inferred_emotion, emotion_confidence,
                desired_tone, support_intent, interaction_goal, caution_flags_json, recent_bond_signals_json,
                recent_sensitive_signals_json, updated_at
            )
            select
                s.session_id, s.principal_id, #{relationalState}, #{inferredEmotion}, #{emotionConfidence},
                #{desiredTone}, #{supportIntent}, #{interactionGoal},
                cast(#{cautionFlagsJson} as jsonb), cast(#{recentBondSignalsJson} as jsonb),
                cast(#{recentSensitiveSignalsJson} as jsonb), current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            on conflict (session_id)
            do update set
                principal_id = excluded.principal_id,
                current_relational_state = excluded.current_relational_state,
                inferred_emotion = excluded.inferred_emotion,
                emotion_confidence = excluded.emotion_confidence,
                desired_tone = excluded.desired_tone,
                support_intent = excluded.support_intent,
                interaction_goal = excluded.interaction_goal,
                caution_flags_json = excluded.caution_flags_json,
                recent_bond_signals_json = excluded.recent_bond_signals_json,
                recent_sensitive_signals_json = excluded.recent_sensitive_signals_json,
                updated_at = current_timestamp
            """)
    int upsertRelationalWorking(@Param("sessionId") String sessionId,
                                @Param("relationalState") String relationalState,
                                @Param("inferredEmotion") String inferredEmotion,
                                @Param("emotionConfidence") double emotionConfidence,
                                @Param("desiredTone") String desiredTone,
                                @Param("supportIntent") String supportIntent,
                                @Param("interactionGoal") String interactionGoal,
                                @Param("cautionFlagsJson") String cautionFlagsJson,
                                @Param("recentBondSignalsJson") String recentBondSignalsJson,
                                @Param("recentSensitiveSignalsJson") String recentSensitiveSignalsJson);

    @Insert("""
            insert into task_semantic_fact(principal_id, scope_type, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at)
            select s.principal_id, 'USER', #{factType}, #{factKey}, #{factValue}, 0.72, 0.72, #{sourceType}, #{sourceRef}, false, current_timestamp, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            """)
    int insertTaskSemanticFact(@Param("sessionId") String sessionId, @Param("factType") String factType, @Param("factKey") String factKey,
                               @Param("factValue") String factValue, @Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef);

    @Insert("""
            insert into relational_semantic_fact(principal_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at)
            select s.principal_id, #{factType}, #{factKey}, #{factValue}, 0.75, 0.75, #{sourceType}, #{sourceRef}, false, current_timestamp, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            """)
    int insertRelationalSemanticFact(@Param("sessionId") String sessionId, @Param("factType") String factType, @Param("factKey") String factKey,
                                     @Param("factValue") String factValue, @Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef);

    @Update("""
            insert into relational_profile(
                principal_id, relationship_stage, preferred_name, preferred_tone, emotional_support_style,
                humor_preference, intimacy_preference, interaction_style_json, boundary_preferences_json,
                sensitive_topics_json, comfort_triggers_json, no_go_patterns_json, trust_score, intimacy_score, created_at, updated_at
            )
            select
                s.principal_id, #{relationshipStage}, #{preferredName}, #{preferredTone}, #{emotionalSupportStyle},
                #{humorPreference}, #{intimacyPreference}, cast(#{interactionStyleJson} as jsonb),
                cast(#{boundaryPreferencesJson} as jsonb), cast(#{sensitiveTopicsJson} as jsonb),
                cast(#{comfortTriggersJson} as jsonb), cast(#{noGoPatternsJson} as jsonb),
                #{trustScore}, #{intimacyScore}, current_timestamp, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            on conflict (principal_id)
            do update set
                relationship_stage = coalesce(excluded.relationship_stage, relational_profile.relationship_stage),
                preferred_name = coalesce(excluded.preferred_name, relational_profile.preferred_name),
                preferred_tone = coalesce(excluded.preferred_tone, relational_profile.preferred_tone),
                emotional_support_style = coalesce(excluded.emotional_support_style, relational_profile.emotional_support_style),
                humor_preference = coalesce(excluded.humor_preference, relational_profile.humor_preference),
                intimacy_preference = coalesce(excluded.intimacy_preference, relational_profile.intimacy_preference),
                interaction_style_json = case when excluded.interaction_style_json = '{}'::jsonb then relational_profile.interaction_style_json else excluded.interaction_style_json end,
                boundary_preferences_json = case when excluded.boundary_preferences_json = '{}'::jsonb then relational_profile.boundary_preferences_json else excluded.boundary_preferences_json end,
                sensitive_topics_json = case when excluded.sensitive_topics_json = '[]'::jsonb then relational_profile.sensitive_topics_json else excluded.sensitive_topics_json end,
                comfort_triggers_json = case when excluded.comfort_triggers_json = '[]'::jsonb then relational_profile.comfort_triggers_json else excluded.comfort_triggers_json end,
                no_go_patterns_json = case when excluded.no_go_patterns_json = '[]'::jsonb then relational_profile.no_go_patterns_json else excluded.no_go_patterns_json end,
                trust_score = greatest(relational_profile.trust_score, excluded.trust_score),
                intimacy_score = greatest(relational_profile.intimacy_score, excluded.intimacy_score),
                updated_at = current_timestamp
            """)
    int upsertRelationalProfile(@Param("sessionId") String sessionId,
                                @Param("relationshipStage") String relationshipStage,
                                @Param("preferredName") String preferredName,
                                @Param("preferredTone") String preferredTone,
                                @Param("emotionalSupportStyle") String emotionalSupportStyle,
                                @Param("humorPreference") String humorPreference,
                                @Param("intimacyPreference") String intimacyPreference,
                                @Param("interactionStyleJson") String interactionStyleJson,
                                @Param("boundaryPreferencesJson") String boundaryPreferencesJson,
                                @Param("sensitiveTopicsJson") String sensitiveTopicsJson,
                                @Param("comfortTriggersJson") String comfortTriggersJson,
                                @Param("noGoPatternsJson") String noGoPatternsJson,
                                @Param("trustScore") double trustScore,
                                @Param("intimacyScore") double intimacyScore);

    @Update("""
            insert into emotional_baseline(
                principal_id, usual_expression_style, stress_signals_json, burnout_signals_json,
                sadness_signals_json, comfort_preferences_json, encouragement_patterns_json,
                escalation_threshold, updated_at
            )
            select s.principal_id, #{usualExpressionStyle}, cast(#{stressSignalsJson} as jsonb), cast(#{burnoutSignalsJson} as jsonb),
                   cast(#{sadnessSignalsJson} as jsonb), cast(#{comfortPreferencesJson} as jsonb), cast(#{encouragementPatternsJson} as jsonb),
                   #{escalationThreshold}, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            on conflict (principal_id)
            do update set
                usual_expression_style = coalesce(excluded.usual_expression_style, emotional_baseline.usual_expression_style),
                stress_signals_json = case when excluded.stress_signals_json = '[]'::jsonb then emotional_baseline.stress_signals_json else excluded.stress_signals_json end,
                burnout_signals_json = case when excluded.burnout_signals_json = '[]'::jsonb then emotional_baseline.burnout_signals_json else excluded.burnout_signals_json end,
                sadness_signals_json = case when excluded.sadness_signals_json = '[]'::jsonb then emotional_baseline.sadness_signals_json else excluded.sadness_signals_json end,
                comfort_preferences_json = case when excluded.comfort_preferences_json = '[]'::jsonb then emotional_baseline.comfort_preferences_json else excluded.comfort_preferences_json end,
                encouragement_patterns_json = case when excluded.encouragement_patterns_json = '[]'::jsonb then emotional_baseline.encouragement_patterns_json else excluded.encouragement_patterns_json end,
                escalation_threshold = excluded.escalation_threshold,
                updated_at = current_timestamp
            """)
    int upsertEmotionalBaseline(@Param("sessionId") String sessionId,
                                @Param("usualExpressionStyle") String usualExpressionStyle,
                                @Param("stressSignalsJson") String stressSignalsJson,
                                @Param("burnoutSignalsJson") String burnoutSignalsJson,
                                @Param("sadnessSignalsJson") String sadnessSignalsJson,
                                @Param("comfortPreferencesJson") String comfortPreferencesJson,
                                @Param("encouragementPatternsJson") String encouragementPatternsJson,
                                @Param("escalationThreshold") double escalationThreshold);

    @Update("""
            insert into relational_boundary_rule(principal_id, rule_type, rule_key, rule_value, confidence_score, source_type, updated_at, created_at)
            select s.principal_id, #{ruleType}, #{ruleKey}, #{ruleValue}, #{confidenceScore}, #{sourceType}, current_timestamp, current_timestamp
            from agent_session s
            where s.session_id = #{sessionId}
            on conflict do nothing
            """)
    int insertRelationalBoundaryRule(@Param("sessionId") String sessionId,
                                     @Param("ruleType") String ruleType,
                                     @Param("ruleKey") String ruleKey,
                                     @Param("ruleValue") String ruleValue,
                                     @Param("confidenceScore") double confidenceScore,
                                     @Param("sourceType") String sourceType);

    @Insert("""
            insert into task_episode(principal_id, session_id, plan_id, episode_type, title, task_goal, trajectory_summary, outcome_summary, outcome_status, lessons_learned, importance_score, reusability_score, created_at)
            select principal_id, #{sessionId}, current_plan_id, #{episodeType}, #{title}, current_goal, #{trajectorySummary}, #{outcomeSummary}, #{outcomeStatus}, #{lessonsLearned}, 0.65, 0.60, current_timestamp
            from agent_session
            where session_id = #{sessionId}
            """)
    int insertTaskEpisode(@Param("sessionId") String sessionId, @Param("episodeType") String episodeType, @Param("title") String title,
                          @Param("trajectorySummary") String trajectorySummary, @Param("outcomeSummary") String outcomeSummary,
                          @Param("outcomeStatus") String outcomeStatus, @Param("lessonsLearned") String lessonsLearned);

    @Select("""
            select episode_id
            from task_episode
            where session_id = #{sessionId}
            order by created_at desc
            limit 1
            """)
    Long selectLatestTaskEpisodeId(@Param("sessionId") String sessionId);

    @Insert("""
            insert into task_episode_step(episode_id, step_order, step_type, title, content_text, payload_json, created_at)
            values (#{episodeId}, #{stepOrder}, #{stepType}, #{title}, #{contentText}, cast(#{payloadJson} as jsonb), current_timestamp)
            """)
    int insertTaskEpisodeStep(@Param("episodeId") Long episodeId,
                              @Param("stepOrder") int stepOrder,
                              @Param("stepType") String stepType,
                              @Param("title") String title,
                              @Param("contentText") String contentText,
                              @Param("payloadJson") String payloadJson);

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
            select 'TASK','WORKING','task_working_memory', cast(twm_id as varchar), principal_id, 'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp
            from task_working_memory
            where session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshTaskWorkingRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','WORKING','relational_working_memory', cast(rwm_id as varchar), principal_id, 'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp
            from relational_working_memory
            where session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshRelationalWorkingRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'TASK','SEMANTIC','task_semantic_fact', cast(fact_id as varchar), principal_id, coalesce(source_type,'SYSTEM'), coalesce(source_ref,'memory_write_pipeline'),
                   confidence_score, 0.70, 0.80, current_timestamp
            from task_semantic_fact
            where source_ref = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshTaskSemanticRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','SEMANTIC','relational_semantic_fact', cast(fact_id as varchar), principal_id, coalesce(source_type,'SYSTEM'), coalesce(source_ref,'memory_write_pipeline'),
                   confidence_score, 0.70, 0.80, current_timestamp
            from relational_semantic_fact
            where source_ref = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshRelationalSemanticRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'TASK','EPISODIC','task_episode', cast(episode_id as varchar), principal_id, 'SYSTEM', 'memory_write_pipeline', 0.70, importance_score, 0.75, current_timestamp
            from task_episode
            where session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshTaskEpisodeRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','EPISODIC','relational_episode', cast(episode_id as varchar), principal_id, 'SYSTEM', 'memory_write_pipeline', 0.70, 0.70, 0.75, current_timestamp
            from relational_episode
            where session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshRelationalEpisodeRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'TASK','PROCEDURAL','task_procedure_pattern', cast(p.procedure_id as varchar), s.principal_id, 'SYSTEM', 'memory_write_pipeline',
                   p.confidence_score, 0.65, 0.60, current_timestamp
            from task_procedure_pattern p
            join agent_session s on s.session_id = #{sessionId}
            where p.name in ('default_task_execution','default_failure_recovery')
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshTaskProcedureRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','PROCEDURAL','relational_procedure_pattern', cast(p.procedure_id as varchar), s.principal_id, 'SYSTEM', 'memory_write_pipeline',
                   p.confidence_score, 0.65, 0.60, current_timestamp
            from relational_procedure_pattern p
            join agent_session s on s.session_id = #{sessionId}
            where p.name in ('default_relational_support','default_relation_repair')
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshRelationalProcedureRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','SEMANTIC','relational_profile', cast(profile_id as varchar), principal_id, 'SYSTEM', 'memory_write_pipeline', 0.78, 0.80, 0.70, current_timestamp
            from relational_profile rp
            join agent_session s on s.principal_id = rp.principal_id
            where s.session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshRelationalProfileRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','SEMANTIC','emotional_baseline', cast(id as varchar), principal_id, 'SYSTEM', 'memory_write_pipeline', 0.76, 0.75, 0.70, current_timestamp
            from emotional_baseline eb
            join agent_session s on s.principal_id = eb.principal_id
            where s.session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshEmotionalBaselineRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at)
            select 'RELATION','SEMANTIC','relational_boundary_rule', cast(id as varchar), principal_id, coalesce(source_type,'SYSTEM'), 'memory_write_pipeline', confidence_score, 0.80, 0.70, current_timestamp
            from relational_boundary_rule r
            join agent_session s on s.principal_id = r.principal_id
            where s.session_id = #{sessionId}
            on conflict (ref_table, ref_id) do nothing
            """)
    int refreshBoundaryRuleRegistry(@Param("sessionId") String sessionId);

    @Update("""
            insert into memory_relation(from_memory_id, to_memory_id, relation_type, weight, created_at)
            select wm.memory_id, m.memory_id, 'DERIVED_FROM', 0.80, current_timestamp
            from memory_registry wm
            join memory_registry m on m.memory_id <> wm.memory_id
            where wm.ref_table = 'task_working_memory'
              and wm.ref_id = (
                    select cast(twm_id as varchar)
                    from task_working_memory
                    where session_id = #{sessionId}
                    limit 1
              )
              and m.source_ref = #{sessionId}
              and m.memory_layer in ('SEMANTIC','EPISODIC','PROCEDURAL')
              and not exists (
                    select 1
                    from memory_relation r
                    where r.from_memory_id = wm.memory_id
                      and r.to_memory_id = m.memory_id
                      and r.relation_type = 'DERIVED_FROM'
              )
            """)
    int upsertWorkingDerivedRelations(@Param("sessionId") String sessionId);
}
