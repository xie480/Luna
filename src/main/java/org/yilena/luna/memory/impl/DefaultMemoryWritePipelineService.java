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
        // 1) Message writer
        insertMessage(sessionId, "USER", userInput);
        insertMessage(sessionId, "ASSISTANT", assistantReply);

        // 2) State updater
        updateSessionState(sessionId, contextPackage);

        // 3) Task working memory updater
        upsertTaskWorkingMemory(sessionId, userInput);

        // 4) Relational working memory updater
        upsertRelationalWorkingMemory(sessionId, contextPackage);

        // 5) Semantic extractor
        extractAndPersistSemanticFacts(sessionId, userInput);

        // 9) Registry updater (minimal bootstrap)
        refreshWorkingMemoryRegistry(sessionId);
    }

    private void insertMessage(String sessionId, String role, String content) {
        try {
            jdbcTemplate.update(
                    "insert into conversation_message(session_id, role, message_type, content_text, created_at) " +
                            "values (?, ?, 'TEXT', ?, current_timestamp)",
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
                    "update agent_session set task_state = ?, relational_state = ?, last_agent_message_at = current_timestamp, updated_at = current_timestamp " +
                            "where session_id = ?",
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
                ? contextPackage.getRelationalState().name() : RelationalRuntimeState.LIGHT_CHAT.name();
        try {
            jdbcTemplate.update(
                    "insert into relational_working_memory(session_id, current_relational_state, desired_tone, updated_at) " +
                            "values (?, ?, ?, current_timestamp) " +
                            "on conflict (session_id) do update set " +
                            "current_relational_state = excluded.current_relational_state, desired_tone = excluded.desired_tone, updated_at = current_timestamp",
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
        if (containsAny(lower, "以后默认", "默认用", "我做", "我负责", "竞品分析默认")) {
            insertTaskSemanticFact(sessionId, "PREFERENCE", "auto_extracted_task_pref", text, "USER_INPUT");
        }
        if (containsAny(lower, "别叫我", "不喜欢太", "难受的时候")) {
            insertRelationalSemanticFact(sessionId, "BOUNDARY", "auto_extracted_relation_boundary", text, "USER_INPUT");
        }
    }

    private void insertTaskSemanticFact(String sessionId, String factType, String factKey, String factValue, String sourceType) {
        try {
            jdbcTemplate.update(
                    "insert into task_semantic_fact(principal_id, scope_type, fact_type, fact_key, fact_value_text, " +
                            "confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at) " +
                            "values (cast(abs(hashtext(?)) as bigint), 'SESSION', ?, ?, ?, 0.7, 0.7, ?, ?, false, current_timestamp, current_timestamp)",
                    sessionId, factType, factKey, factValue, sourceType, sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void insertRelationalSemanticFact(String sessionId, String factType, String factKey, String factValue, String sourceType) {
        try {
            jdbcTemplate.update(
                    "insert into relational_semantic_fact(principal_id, fact_type, fact_key, fact_value_text, " +
                            "confidence_score, stability_score, source_type, source_ref, deleted, created_at, updated_at) " +
                            "values (cast(abs(hashtext(?)) as bigint), ?, ?, ?, 0.75, 0.75, ?, ?, false, current_timestamp, current_timestamp)",
                    sessionId, factType, factKey, factValue, sourceType, sessionId
            );
        } catch (Exception ignore) {
        }
    }

    private void refreshWorkingMemoryRegistry(String sessionId) {
        try {
            jdbcTemplate.update(
                    "insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, " +
                            "confidence_score, importance_score, freshness_score, created_at) " +
                            "select 'TASK','WORKING','task_working_memory', cast(twm_id as varchar), cast(abs(hashtext(?)) as bigint), " +
                            "'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp " +
                            "from task_working_memory where session_id = ? " +
                            "on conflict (ref_table, ref_id) do nothing",
                    sessionId, sessionId
            );
            jdbcTemplate.update(
                    "insert into memory_registry(memory_domain, memory_layer, ref_table, ref_id, principal_id, source_type, source_ref, " +
                            "confidence_score, importance_score, freshness_score, created_at) " +
                            "select 'RELATION','WORKING','relational_working_memory', cast(rwm_id as varchar), cast(abs(hashtext(?)) as bigint), " +
                            "'SYSTEM','memory_write_pipeline',0.8,0.8,1.0,current_timestamp " +
                            "from relational_working_memory where session_id = ? " +
                            "on conflict (ref_table, ref_id) do nothing",
                    sessionId, sessionId
            );
        } catch (Exception ignore) {
        }
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
