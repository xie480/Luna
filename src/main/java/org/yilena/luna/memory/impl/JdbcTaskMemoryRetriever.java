package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.memory.TaskMemoryRetriever;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcTaskMemoryRetriever implements TaskMemoryRetriever {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Object> retrieve(String sessionId, String userInput) {
        Map<String, Object> result = new HashMap<>();
        result.put("working_memory", queryOne(
                "select * from task_working_memory where session_id = ?",
                sessionId
        ));
        result.put("task_facts", queryList(
                "select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, updated_at " +
                        "from task_semantic_fact where deleted = false and " +
                        "(scope_type in ('GLOBAL','SESSION') or principal_id = cast(abs(hashtext(?)) as bigint)) " +
                        "order by updated_at desc limit 20",
                sessionId
        ));
        result.put("task_episodes", queryList(
                "select episode_id, episode_type, title, trajectory_summary, lessons_learned, created_at " +
                        "from task_episode where session_id = ? order by created_at desc limit 8",
                sessionId
        ));
        result.put("task_procedures", queryList(
                "select procedure_id, procedure_type, name, description, confidence_score, usage_count " +
                        "from task_procedure_pattern order by confidence_score desc, usage_count desc limit 8"
        ));
        result.put("knowledge", queryList(
                "select kc.chunk_id, kd.title, kc.chunk_text, kc.chunk_summary, kc.created_at " +
                        "from knowledge_chunk kc join knowledge_document kd on kd.doc_id = kc.doc_id " +
                        "order by kc.created_at desc limit 10"
        ));
        result.put("plan_context", queryOne(
                "select id, plan_id, node_id, context_package_json, created_at " +
                        "from plan_context_snapshot where session_id = ? order by created_at desc limit 1",
                sessionId
        ));
        return result;
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
            return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> queryList(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForList(sql, args);
        } catch (Exception ignore) {
            return Collections.emptyList();
        }
    }
}
