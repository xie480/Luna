package org.yilena.luna.memory.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yilena.luna.memory.RuntimeRetriever;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JdbcRuntimeRetriever implements RuntimeRetriever {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<String, Object> retrieve(String sessionId) {
        Map<String, Object> result = new HashMap<>();
        result.put("session", queryOne(
                "select session_id, session_type, task_state, relational_state, current_plan_id, current_goal, " +
                        "last_user_message_at, last_agent_message_at, metadata_json " +
                        "from agent_session where session_id = ?",
                sessionId
        ));
        result.put("recent_messages", queryList(
                "select message_id, role, message_type, content_text, trace_id, created_at " +
                        "from conversation_message where session_id = ? order by created_at desc limit 12",
                sessionId
        ));
        result.put("active_tool_results", queryList(
                "select tool_name, call_status, normalized_output, error_message, created_at " +
                        "from tool_execution_trace where session_id = ? order by created_at desc limit 8",
                sessionId
        ));
        result.put("context_snapshots", queryList(
                "select id, plan_id, node_id, created_at from plan_context_snapshot where session_id = ? " +
                        "order by created_at desc limit 3",
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
