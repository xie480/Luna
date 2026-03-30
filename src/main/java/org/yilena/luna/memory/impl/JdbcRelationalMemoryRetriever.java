package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.memory.RelationalMemoryRetriever;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcRelationalMemoryRetriever implements RelationalMemoryRetriever {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Object> retrieve(String sessionId, String userInput) {
        Map<String, Object> result = new HashMap<>();
        result.put("working_memory", queryOne(
                "select * from relational_working_memory where session_id = ?",
                sessionId
        ));
        result.put("profile", queryOne(
                "select rp.* from relational_profile rp " +
                        "join agent_session s on s.principal_id = rp.principal_id where s.session_id = ?",
                sessionId
        ));
        result.put("semantic_facts", queryList(
                "select rsf.fact_id, rsf.fact_type, rsf.fact_key, rsf.fact_value_text, rsf.description, rsf.confidence_score, rsf.updated_at " +
                        "from relational_semantic_fact rsf " +
                        "join agent_session s on (s.principal_id = rsf.principal_id or rsf.principal_id is null) " +
                        "where s.session_id = ? and rsf.deleted = false " +
                        "order by rsf.updated_at desc limit 20",
                sessionId
        ));
        result.put("episodes", queryList(
                "select episode_id, episode_type, title, summary, support_style_used, interaction_quality, created_at " +
                        "from relational_episode where session_id = ? order by created_at desc limit 8",
                sessionId
        ));
        result.put("procedures", queryList(
                "select procedure_id, procedure_type, name, description, confidence_score, usage_count " +
                        "from relational_procedure_pattern order by confidence_score desc, usage_count desc limit 8"
        ));
        result.put("emotional_baseline", queryOne(
                "select eb.* from emotional_baseline eb " +
                        "join agent_session s on s.principal_id = eb.principal_id where s.session_id = ?",
                sessionId
        ));
        result.put("boundary_rules", queryList(
                "select rbr.id, rbr.rule_type, rbr.rule_key, rbr.rule_value, rbr.confidence_score, rbr.updated_at " +
                        "from relational_boundary_rule rbr " +
                        "join agent_session s on s.principal_id = rbr.principal_id where s.session_id = ? " +
                        "order by rbr.updated_at desc limit 10",
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
