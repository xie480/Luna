package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.mapper.ToolMemoryMapper;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MemoryTools extends BaseTool {

    private final ToolMemoryMapper toolMemoryMapper;
    private final LlmClientUtil llmClientUtil;

    public MemoryTools(ObjectMapper objectMapper, ToolMemoryMapper toolMemoryMapper, LlmClientUtil llmClientUtil) {
        super(objectMapper);
        this.toolMemoryMapper = toolMemoryMapper;
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
        toolMemoryMapper.upsertTaskWorkingMemory(normalizeSessionId(sessionId), content);
        return Map.of("table", "task_working_memory", "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertRelationalWorkingMemory(String sessionId, String content) {
        toolMemoryMapper.upsertRelationalWorkingMemory(normalizeSessionId(sessionId), content);
        return Map.of("table", "relational_working_memory", "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertTaskSemanticFact(String sessionId, String factType, String factKey, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertTaskSemanticFact(normalizeSessionId(sessionId), factType, factKey, content, normalizeEmbedding(embedding));
        return Map.of("table", "task_semantic_fact", "fact_type", factType, "fact_key", factKey);
    }

    private Map<String, Object> insertRelationalSemanticFact(String sessionId, String factType, String factKey, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertRelationalSemanticFact(normalizeSessionId(sessionId), factType, factKey, content, normalizeEmbedding(embedding));
        return Map.of("table", "relational_semantic_fact", "fact_type", factType, "fact_key", factKey);
    }

    private Map<String, Object> insertTaskEpisode(String sessionId, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertTaskEpisode(normalizeSessionId(sessionId), content, normalizeEmbedding(embedding));
        return Map.of("table", "task_episode", "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertRelationalEpisode(String sessionId, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertRelationalEpisode(normalizeSessionId(sessionId), content, normalizeEmbedding(embedding));
        return Map.of("table", "relational_episode", "session_id", normalizeSessionId(sessionId));
    }

    private List<Map<String, Object>> queryMemory(String domain, String layer, String sessionId) {
        String sid = normalizeSessionId(sessionId);
        if ("TASK".equals(domain) && "WORKING".equals(layer)) {
            return toolMemoryMapper.queryTaskWorkingMemory(sid);
        }
        if ("RELATION".equals(domain) && "WORKING".equals(layer)) {
            return toolMemoryMapper.queryRelationalWorkingMemory(sid);
        }
        if ("TASK".equals(domain) && "SEMANTIC".equals(layer)) {
            return toolMemoryMapper.queryTaskSemanticFacts(sid);
        }
        if ("RELATION".equals(domain) && "SEMANTIC".equals(layer)) {
            return toolMemoryMapper.queryRelationalSemanticFacts(sid);
        }
        if ("TASK".equals(domain) && "EPISODIC".equals(layer)) {
            return toolMemoryMapper.queryTaskEpisodes(sid);
        }
        if ("RELATION".equals(domain) && "EPISODIC".equals(layer)) {
            return toolMemoryMapper.queryRelationalEpisodes(sid);
        }
        return Collections.emptyList();
    }

    private Map<String, Object> deleteHard(String domain, String layer, Long id) {
        String table = resolveTable(domain, layer);
        switch (table) {
            case "task_working_memory" -> toolMemoryMapper.deleteTaskWorkingMemory(id);
            case "relational_working_memory" -> toolMemoryMapper.deleteRelationalWorkingMemory(id);
            case "task_semantic_fact" -> toolMemoryMapper.deleteTaskSemanticFact(id);
            case "relational_semantic_fact" -> toolMemoryMapper.deleteRelationalSemanticFact(id);
            case "task_episode" -> toolMemoryMapper.deleteTaskEpisode(id);
            case "relational_episode" -> toolMemoryMapper.deleteRelationalEpisode(id);
            default -> throw new IllegalArgumentException("unsupported table");
        }
        return Map.of("table", table, "id", id, "deleted", "hard");
    }

    private Map<String, Object> deleteSoft(String domain, String layer, Long id) {
        String table = resolveTable(domain, layer);
        if ("task_semantic_fact".equals(table) || "relational_semantic_fact".equals(table)) {
            if ("task_semantic_fact".equals(table)) {
                toolMemoryMapper.softDeleteTaskSemanticFact(id);
            } else {
                toolMemoryMapper.softDeleteRelationalSemanticFact(id);
            }
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
