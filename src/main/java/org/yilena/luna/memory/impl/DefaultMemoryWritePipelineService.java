package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.MemoryWritePipelineService;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DefaultMemoryWritePipelineService implements MemoryWritePipelineService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void writeAfterTurn(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        insertMessage(sessionId, "USER", userInput);
        insertMessage(sessionId, "ASSISTANT", assistantReply);
        updateSessionState(sessionId, contextPackage);
        upsertTaskWorkingMemory(sessionId, userInput);
        upsertRelationalWorkingMemory(sessionId, contextPackage);
        extractAndPersistSemanticFacts(sessionId, userInput);
        buildEpisodes(sessionId, userInput, assistantReply, contextPackage);
        reflectAndMineProcedures(sessionId, userInput, assistantReply, contextPackage);
        updateProcedureStatistics(userInput, contextPackage);
        refreshWorkingMemoryRegistry(sessionId);
    }

    private void insertMessage(String sessionId, String role, String content) {
        try {
            jdbcTemplate.update(
                    "insert into conversation_message(session_id, role, message_type, content_text, created_at) values (?, ?, 'TEXT', ?, current_timestamp)",
                    sessionId, role, content
            );
        } catch (Exception ignore) {
        }
    }

    private void updateSessionState(String sessionId, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationalState = contextPackage.getRelationalState();
        try {
            jdbcTemplate.update(
                    "update agent_session set task_state = ?, relational_state = ?, last_agent_message_at = current_timestamp, updated_at = current_timestamp where session_id = ?",
                    taskState != null ? taskState.name() : TaskRuntimeState.UNDERSTANDING.name(),
                    relationalState != null ? relationalState.name() : RelationalRuntimeState.LIGHT_CHAT.name(),
                    sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void upsertTaskWorkingMemory(String sessionId, String userInput) {
        try {
            jdbcTemplate.update(
                    "insert into task_working_memory(session_id, goal_raw, goal_refined, intent_json, version, updated_at) " +
                            "values (?, ?, ?, jsonb_build_object('source','memory_write_pipeline'), 1, current_timestamp) " +
                            "on conflict (session_id) do update set goal_raw = excluded.goal_raw, goal_refined = excluded.goal_refined, " +
                            "version = task_working_memory.version + 1, updated_at = current_timestamp",
                    sessionId, userInput, userInput
            );
        } catch (Exception ignore) {
        }
    }

    private void upsertRelationalWorkingMemory(String sessionId, StructuredContextPackage contextPackage) {
        String relationalState = contextPackage != null && contextPackage.getRelationalState() != null
                ? contextPackage.getRelationalState().name()
                : RelationalRuntimeState.LIGHT_CHAT.name();
        try {
            jdbcTemplate.update(
                    "insert into relational_working_memory(session_id, current_relational_state, desired_tone, updated_at) " +
                            "values (?, ?, ?, current_timestamp) " +
                            "on conflict (session_id) do update set current_relational_state = excluded.current_relational_state, desired_tone = excluded.desired_tone, updated_at = current_timestamp",
                    sessionId, relationalState, inferTone(relationalState)
            );
        } catch (Exception ignore) {
        }
    }

    private String inferTone(String relationalState) {
        if ("EMOTIONAL_SUPPORT".equals(relationalState) || "FRAGILE_MOMENT".equals(relationalState)) {
            return "soft_and_calm";
        }
        if ("CELEBRATING".equals(relationalState)) {
            return "warm_and_positive";
        }
        return "clear_and_friendly";
    }

    private void extractAndPersistSemanticFacts(String sessionId, String userInput) {
        String text = userInput == null ? "" : userInput.trim();
        if (text.isEmpty()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "default", "prefer", "markdown", "format", "style")) {
            insertTaskSemanticFact(sessionId, "PREFERENCE", "auto_extracted_task_pref", text, "USER_INPUT");
        }
        if (containsAny(lower, "do not call me", "don't lecture", "uncomfortable", "need support first")) {
            insertRelationalSemanticFact(sessionId, "BOUNDARY", "auto_extracted_relation_boundary", text, "USER_INPUT");
        }
    }

    private void insertTaskSemanticFact(String sessionId, String factType, String factKey, String factValue, String sourceType) {
        try {
            jdbcTemplate.update(
                    "insert into task_semantic_fact(principal_id, scope_type, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at) " +
                            "values (cast(abs(hashtext(?)) as bigint), 'SESSION', ?, ?, ?, 0.7, 0.7, ?, ?, false, current_timestamp, current_timestamp)",
                    sessionId, factType, factKey, factValue, sourceType, sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void insertRelationalSemanticFact(String sessionId, String factType, String factKey, String factValue, String sourceType) {
        try {
            jdbcTemplate.update(
                    "insert into relational_semantic_fact(principal_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at) " +
                            "values (cast(abs(hashtext(?)) as bigint), ?, ?, ?, 0.75, 0.75, ?, ?, false, current_timestamp, current_timestamp)",
                    sessionId, factType, factKey, factValue, sourceType, sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void buildEpisodes(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();

        if (taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REPORTING) {
            try {
                jdbcTemplate.update(
                        "insert into task_episode(principal_id, session_id, plan_id, episode_type, title, task_goal, trajectory_summary, outcome_summary, outcome_status, lessons_learned, importance_score, reusability_score, created_at) " +
                                "select principal_id, ?, current_plan_id, ?, ?, current_goal, ?, ?, ?, ?, 0.65, 0.60, current_timestamp from agent_session where session_id = ?",
                        sessionId,
                        taskState == TaskRuntimeState.FAILED ? "FAILURE" : "SUCCESS",
                        summarize(userInput, 96),
                        summarize(userInput, 300),
                        summarize(assistantReply, 400),
                        taskState.name(),
                        taskState == TaskRuntimeState.FAILED ? "needs_replan" : "successful_turn",
                        sessionId
                );
            } catch (Exception ignore) {
            }
        }

        if (relationState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationState == RelationalRuntimeState.REPAIRING
                || relationState == RelationalRuntimeState.CELEBRATING
                || relationState == RelationalRuntimeState.FRAGILE_MOMENT) {
            try {
                jdbcTemplate.update(
                        "insert into relational_episode(principal_id, session_id, episode_type, title, summary, emotion_before, emotion_after, support_style_used, interaction_quality, response_effectiveness, created_at) " +
                                "select principal_id, ?, ?, ?, ?, ?, ?, ?, 0.70, 0.70, current_timestamp from agent_session where session_id = ?",
                        sessionId,
                        relationState == RelationalRuntimeState.CELEBRATING ? "CELEBRATION" : (relationState == RelationalRuntimeState.REPAIRING ? "REPAIR" : "COMFORT"),
                        summarize(userInput, 96),
                        summarize(assistantReply, 320),
                        relationState.name(),
                        relationState.name(),
                        inferTone(relationState.name()),
                        sessionId
                );
            } catch (Exception ignore) {
            }
        }
    }

    private void reflectAndMineProcedures(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        ensureTaskExecutionProcedure();
        ensureRelationalSupportProcedure();

        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        if (taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REFLECTING) {
            writeTaskReflection(sessionId, userInput, assistantReply, taskState);
            ensureTaskRecoveryProcedure();
        }
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (relationState == RelationalRuntimeState.REPAIRING
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way")) {
            writeRelationalReflection(sessionId, userInput, assistantReply);
            ensureRelationalRepairProcedure();
        }
    }

    private void updateProcedureStatistics(String userInput, StructuredContextPackage contextPackage) {
        if (contextPackage == null) {
            return;
        }
        TaskRuntimeState taskState = contextPackage.getTaskState();
        RelationalRuntimeState relationState = contextPackage.getRelationalState();
        boolean taskSuccess = taskState == TaskRuntimeState.COMPLETED || taskState == TaskRuntimeState.REPORTING;
        boolean taskFailure = taskState == TaskRuntimeState.FAILED || taskState == TaskRuntimeState.REFLECTING;
        String lower = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);

        try {
            jdbcTemplate.update(
                    "update task_procedure_pattern set usage_count = usage_count + 1, success_count = success_count + ?, fail_count = fail_count + ?, updated_at = current_timestamp where name = 'default_task_execution'",
                    taskSuccess ? 1 : 0,
                    taskFailure ? 1 : 0
            );
            if (taskFailure) {
                jdbcTemplate.update(
                        "update task_procedure_pattern set usage_count = usage_count + 1, fail_count = fail_count + 1, updated_at = current_timestamp where name = 'default_failure_recovery'"
                );
            }
        } catch (Exception ignore) {
        }

        boolean relationEngaged = relationState == RelationalRuntimeState.EMOTIONAL_SUPPORT
                || relationState == RelationalRuntimeState.FRAGILE_MOMENT
                || relationState == RelationalRuntimeState.REPAIRING
                || relationState == RelationalRuntimeState.CELEBRATING;
        if (!relationEngaged) {
            return;
        }
        boolean relationFailure = relationState == RelationalRuntimeState.REPAIRING
                || containsAny(lower, "you don't get me", "offended", "uncomfortable", "not this way");
        try {
            jdbcTemplate.update(
                    "update relational_procedure_pattern set usage_count = usage_count + 1, success_count = success_count + ?, fail_count = fail_count + ?, updated_at = current_timestamp where name = 'default_relational_support'",
                    relationFailure ? 0 : 1,
                    relationFailure ? 1 : 0
            );
            if (relationFailure) {
                jdbcTemplate.update(
                        "update relational_procedure_pattern set usage_count = usage_count + 1, fail_count = fail_count + 1, updated_at = current_timestamp where name = 'default_relation_repair'"
                );
            }
        } catch (Exception ignore) {
        }
    }

    private void writeTaskReflection(String sessionId, String userInput, String assistantReply, TaskRuntimeState taskState) {
        try {
            jdbcTemplate.update(
                    "insert into task_reflection_record(plan_id, node_id, reflection_type, trigger_reason, observation, root_cause, proposed_fix, extracted_pattern_json, quality_score, created_at) " +
                            "select current_plan_id, null, ?, ?, ?, ?, ?, jsonb_build_object('source','memory_write_pipeline'), 0.65, current_timestamp from agent_session where session_id = ?",
                    taskState.name(),
                    "task_state_trigger",
                    summarize(userInput, 220),
                    "execution_quality_risk",
                    summarize(assistantReply, 220),
                    sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void writeRelationalReflection(String sessionId, String userInput, String assistantReply) {
        try {
            jdbcTemplate.update(
                    "insert into relational_reflection_record(session_id, reflection_type, trigger_reason, observation, root_cause, proposed_fix, extracted_pattern_json, quality_score, created_at) " +
                            "values (?, 'MISALIGNMENT', 'user_signal', ?, ?, ?, jsonb_build_object('source','memory_write_pipeline'), 0.68, current_timestamp)",
                    sessionId,
                    summarize(userInput, 220),
                    "tone_or_understanding_gap",
                    summarize(assistantReply, 220)
            );
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskExecutionProcedure() {
        try {
            jdbcTemplate.update(
                    "insert into task_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at) " +
                            "select 'VALIDATION', 'default_task_execution', 'default execution quality baseline', jsonb_build_object('trigger','task_turn'), jsonb_build_array('understand','execute_or_plan','validate'), " +
                            "'ONLINE_RUNTIME', 0.60, 0, 0, 0, current_timestamp, current_timestamp " +
                            "where not exists (select 1 from task_procedure_pattern where name = 'default_task_execution')"
            );
        } catch (Exception ignore) {
        }
    }

    private void ensureTaskRecoveryProcedure() {
        try {
            jdbcTemplate.update(
                    "insert into task_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at) " +
                            "select 'RECOVERY', 'default_failure_recovery', 'fallback recovery after failed turn', jsonb_build_object('trigger','task_failed'), jsonb_build_array('analyze_root_cause','replan','validate'), " +
                            "'ONLINE_REFLECTION', 0.62, 0, 0, 0, current_timestamp, current_timestamp " +
                            "where not exists (select 1 from task_procedure_pattern where name = 'default_failure_recovery')"
            );
        } catch (Exception ignore) {
        }
    }

    private void ensureRelationalSupportProcedure() {
        try {
            jdbcTemplate.update(
                    "insert into relational_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at) " +
                            "select 'COMFORT_PATTERN', 'default_relational_support', 'default relational support baseline', jsonb_build_object('trigger','relational_turn'), jsonb_build_array('attune','respond','confirm'), " +
                            "'ONLINE_RUNTIME', 0.60, 0, 0, 0, current_timestamp, current_timestamp " +
                            "where not exists (select 1 from relational_procedure_pattern where name = 'default_relational_support')"
            );
        } catch (Exception ignore) {
        }
    }

    private void ensureRelationalRepairProcedure() {
        try {
            jdbcTemplate.update(
                    "insert into relational_procedure_pattern(procedure_type, name, description, trigger_conditions_json, pattern_steps_json, source_kind, confidence_score, usage_count, success_count, fail_count, created_at, updated_at) " +
                            "select 'REPAIR_PATTERN', 'default_relation_repair', 'repair after user discomfort', jsonb_build_object('trigger','relation_misalignment'), jsonb_build_array('acknowledge','align','confirm'), " +
                            "'ONLINE_REFLECTION', 0.66, 0, 0, 0, current_timestamp, current_timestamp " +
                            "where not exists (select 1 from relational_procedure_pattern where name = 'default_relation_repair')"
            );
        } catch (Exception ignore) {
        }
    }

    private void refreshWorkingMemoryRegistry(String sessionId) {
        try {
            jdbcTemplate.update(
                    "insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at) " +
                            "select 'TASK','WORKING','task_working_memory', cast(twm_id as varchar), cast(abs(hashtext(?)) as bigint), 'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp " +
                            "from task_working_memory where session_id = ? on conflict (ref_table, ref_id) do nothing",
                    sessionId, sessionId
            );
            jdbcTemplate.update(
                    "insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, confidence_score, importance_score, freshness_score, created_at) " +
                            "select 'RELATION','WORKING','relational_working_memory', cast(rwm_id as varchar), cast(abs(hashtext(?)) as bigint), 'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp " +
                            "from relational_working_memory where session_id = ? on conflict (ref_table, ref_id) do nothing",
                    sessionId, sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private String summarize(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
