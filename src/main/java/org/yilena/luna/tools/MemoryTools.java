package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MemoryTools extends BaseTool {

    private final JdbcTemplate jdbcTemplate;
    private final LlmClientUtil llmClientUtil;

    public MemoryTools(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, LlmClientUtil llmClientUtil) {
        super(objectMapper);
        this.jdbcTemplate = jdbcTemplate;
        this.llmClientUtil = llmClientUtil;
    }

    @LunaState(value = LunaStateConstant.VALUE_MEMORY, status = LunaStateConstant.STATUS_MEMORY)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_MEMORY, type = LogType.TOOL_CALL, content = "manage memory v2")
    public String manageMemory(
            @RequestParam("action") String action,
            @RequestParam(value = "id", required = false) Long id,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "memoryDomain", required = false) String memoryDomain,
            @RequestParam(value = "memoryLayer", required = false) String memoryLayer,
            @RequestParam(value = "factType", required = false) String factType,
            @RequestParam(value = "factKey", required = false) String factKey,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "hardDelete", required = false) Boolean hardDelete) {
        try {
            String domain = normalize(memoryDomain, "TASK");
            String layer = normalize(memoryLayer, "SEMANTIC");

            if ("INSERT".equalsIgnoreCase(action)) {
                if (content == null || content.isBlank()) {
                    return error("INSERT requires content");
                }
                if ("TASK".equals(domain) && "WORKING".equals(layer)) {
                    return success(insertTaskWorkingMemory(sessionId, content));
                }
                if ("RELATION".equals(domain) && "WORKING".equals(layer)) {
                    return success(insertRelationalWorkingMemory(sessionId, content));
                }
                if ("TASK".equals(domain) && "SEMANTIC".equals(layer)) {
                    return success(insertTaskSemanticFact(sessionId, normalize(factType, "DOMAIN_FACT"), normalize(factKey, "manual_fact"), content));
                }
                if ("RELATION".equals(domain) && "SEMANTIC".equals(layer)) {
                    return success(insertRelationalSemanticFact(sessionId, normalize(factType, "INTERACTION_STYLE"), normalize(factKey, "manual_relation_fact"), content));
                }
                if ("TASK".equals(domain) && "EPISODIC".equals(layer)) {
                    return success(insertTaskEpisode(sessionId, content));
                }
                if ("RELATION".equals(domain) && "EPISODIC".equals(layer)) {
                    return success(insertRelationalEpisode(sessionId, content));
                }
                return error("unsupported domain/layer");
            }

            if ("QUERY".equalsIgnoreCase(action)) {
                return success(queryMemory(domain, layer, sessionId));
            }

            if ("DELETE".equalsIgnoreCase(action)) {
                if (id == null) {
                    return error("DELETE requires id");
                }
                if (Boolean.TRUE.equals(hardDelete)) {
                    return success(deleteHard(domain, layer, id));
                }
                return success(deleteSoft(domain, layer, id));
            }

            return error("unknown action: " + action);
        } catch (Exception e) {
            return error("operation failed: " + e.getMessage());
        }
    }

    private Map<String, Object> insertTaskWorkingMemory(String sessionId, String content) {
        jdbcTemplate.update(
                "insert into task_working_memory(session_id, goal_raw, goal_refined, version, updated_at) " +
                        "values (?, ?, ?, 1, current_timestamp) " +
                        "on conflict (session_id) do update set goal_raw = excluded.goal_raw, goal_refined = excluded.goal_refined, " +
                        "version = task_working_memory.version + 1, updated_at = current_timestamp",
                normalizeSessionId(sessionId), content, content
        );
        return Map.of("table", "task_working_memory", "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertRelationalWorkingMemory(String sessionId, String content) {
        jdbcTemplate.update(
                "insert into relational_working_memory(session_id, current_relational_state, interaction_goal, desired_tone, updated_at) " +
                        "values (?, 'LIGHT_CHAT', ?, 'clear_and_friendly', current_timestamp) " +
                        "on conflict (session_id) do update set interaction_goal = excluded.interaction_goal, updated_at = current_timestamp",
                normalizeSessionId(sessionId), content
        );
        return Map.of("table", "relational_working_memory", "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertTaskSemanticFact(String sessionId, String factType, String factKey, String content) {
        String embedding = safeEmbedding(content);
        jdbcTemplate.update(
                "insert into task_semantic_fact(principal_id, scope_type, fact_type, fact_key, fact_value_text, " +
                        "confidence_score, stability_score, source_type, source_ref, embedding, deleted, created_at, updated_at) " +
                        "values (cast(abs(hashtext(?)) as bigint), 'SESSION', ?, ?, ?, 0.8, 0.7, 'TOOL_MANAGE_MEMORY', ?, ?::vector, false, current_timestamp, current_timestamp)",
                normalizeSessionId(sessionId), factType, factKey, content, normalizeSessionId(sessionId), normalizeEmbedding(embedding)
        );
        return Map.of("table", "task_semantic_fact", "fact_type", factType, "fact_key", factKey);
    }

    private Map<String, Object> insertRelationalSemanticFact(String sessionId, String factType, String factKey, String content) {
        String embedding = safeEmbedding(content);
        jdbcTemplate.update(
                "insert into relational_semantic_fact(principal_id, fact_type, fact_key, fact_value_text, " +
                        "confidence_score, stability_score, source_type, source_ref, embedding, deleted, created_at, updated_at) " +
                        "values (cast(abs(hashtext(?)) as bigint), ?, ?, ?, 0.8, 0.7, 'TOOL_MANAGE_MEMORY', ?, ?::vector, false, current_timestamp, current_timestamp)",
                normalizeSessionId(sessionId), factType, factKey, content, normalizeSessionId(sessionId), normalizeEmbedding(embedding)
        );
        return Map.of("table", "relational_semantic_fact", "fact_type", factType, "fact_key", factKey);
    }

    private Map<String, Object> insertTaskEpisode(String sessionId, String content) {
        String embedding = safeEmbedding(content);
        jdbcTemplate.update(
                "insert into task_episode(principal_id, session_id, episode_type, title, trajectory_summary, importance_score, reusability_score, embedding, created_at) " +
                        "values (cast(abs(hashtext(?)) as bigint), ?, 'PARTIAL', left(?, 120), ?, 0.6, 0.6, ?::vector, current_timestamp)",
                normalizeSessionId(sessionId), normalizeSessionId(sessionId), content, content, normalizeEmbedding(embedding)
        );
        return Map.of("table", "task_episode", "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertRelationalEpisode(String sessionId, String content) {
        String embedding = safeEmbedding(content);
        jdbcTemplate.update(
                "insert into relational_episode(principal_id, session_id, episode_type, title, summary, interaction_quality, response_effectiveness, embedding, created_at) " +
                        "values (cast(abs(hashtext(?)) as bigint), ?, 'BONDING', left(?, 120), ?, 0.7, 0.7, ?::vector, current_timestamp)",
                normalizeSessionId(sessionId), normalizeSessionId(sessionId), content, content, normalizeEmbedding(embedding)
        );
        return Map.of("table", "relational_episode", "session_id", normalizeSessionId(sessionId));
    }

    private List<Map<String, Object>> queryMemory(String domain, String layer, String sessionId) {
        String sid = normalizeSessionId(sessionId);
        if ("TASK".equals(domain) && "WORKING".equals(layer)) {
            return jdbcTemplate.queryForList("select * from task_working_memory where session_id = ?", sid);
        }
        if ("RELATION".equals(domain) && "WORKING".equals(layer)) {
            return jdbcTemplate.queryForList("select * from relational_working_memory where session_id = ?", sid);
        }
        if ("TASK".equals(domain) && "SEMANTIC".equals(layer)) {
            return jdbcTemplate.queryForList(
                    "select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, created_at, updated_at " +
                            "from task_semantic_fact where (principal_id = cast(abs(hashtext(?)) as bigint) or principal_id is null) and deleted = false " +
                            "order by updated_at desc limit 50",
                    sid
            );
        }
        if ("RELATION".equals(domain) && "SEMANTIC".equals(layer)) {
            return jdbcTemplate.queryForList(
                    "select fact_id, fact_type, fact_key, fact_value_text, confidence_score, stability_score, created_at, updated_at " +
                            "from relational_semantic_fact where (principal_id = cast(abs(hashtext(?)) as bigint) or principal_id is null) and deleted = false " +
                            "order by updated_at desc limit 50",
                    sid
            );
        }
        if ("TASK".equals(domain) && "EPISODIC".equals(layer)) {
            return jdbcTemplate.queryForList("select * from task_episode where session_id = ? order by created_at desc limit 30", sid);
        }
        if ("RELATION".equals(domain) && "EPISODIC".equals(layer)) {
            return jdbcTemplate.queryForList("select * from relational_episode where session_id = ? order by created_at desc limit 30", sid);
        }
        return Collections.emptyList();
    }

    private Map<String, Object> deleteHard(String domain, String layer, Long id) {
        String table = resolveTable(domain, layer);
        jdbcTemplate.update("delete from " + table + " where " + resolvePk(table) + " = ?", id);
        return Map.of("table", table, "id", id, "deleted", "hard");
    }

    private Map<String, Object> deleteSoft(String domain, String layer, Long id) {
        String table = resolveTable(domain, layer);
        if ("task_semantic_fact".equals(table) || "relational_semantic_fact".equals(table)) {
            jdbcTemplate.update("update " + table + " set deleted = true, updated_at = current_timestamp where fact_id = ?", id);
            return Map.of("table", table, "id", id, "deleted", "soft");
        }
        return deleteHard(domain, layer, id);
    }

    private String resolveTable(String domain, String layer) {
        if ("TASK".equals(domain) && "WORKING".equals(layer)) return "task_working_memory";
        if ("RELATION".equals(domain) && "WORKING".equals(layer)) return "relational_working_memory";
        if ("TASK".equals(domain) && "SEMANTIC".equals(layer)) return "task_semantic_fact";
        if ("RELATION".equals(domain) && "SEMANTIC".equals(layer)) return "relational_semantic_fact";
        if ("TASK".equals(domain) && "EPISODIC".equals(layer)) return "task_episode";
        if ("RELATION".equals(domain) && "EPISODIC".equals(layer)) return "relational_episode";
        throw new IllegalArgumentException("unsupported domain/layer");
    }

    private String resolvePk(String table) {
        if ("task_working_memory".equals(table)) return "twm_id";
        if ("relational_working_memory".equals(table)) return "rwm_id";
        if ("task_semantic_fact".equals(table)) return "fact_id";
        if ("relational_semantic_fact".equals(table)) return "fact_id";
        if ("task_episode".equals(table)) return "episode_id";
        if ("relational_episode".equals(table)) return "episode_id";
        return "id";
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT);
    }

    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default-session" : sessionId;
    }

    private String normalizeEmbedding(String embedding) {
        if (embedding == null || embedding.isBlank() || "[]".equals(embedding.trim())) {
            return "[" + "0,".repeat(767) + "0]";
        }
        return embedding;
    }

    private String safeEmbedding(String content) {
        try {
            return llmClientUtil.getEmbedding(content);
        } catch (Exception ignore) {
            return null;
        }
    }
}
