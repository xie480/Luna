package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.constants.SessionConstant;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.MemoryDomainEnum;
import org.yilena.luna.enums.MemoryLayerEnum;
import org.yilena.luna.enums.ToolActionEnum;
import org.yilena.luna.mapper.ToolMemoryMapper;
import org.yilena.luna.utils.LlmClientUtil;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MemoryTools extends BaseTool {

    private static final String ERROR_UNSUPPORTED_DOMAIN_LAYER = "unsupported domain/layer";
    private static final String ERROR_UNSUPPORTED_TABLE = "unsupported table";
    private static final String ERROR_UNKNOWN_ACTION = "unknown action: ";
    private static final String DELETE_MODE_HARD = "hard";
    private static final String DELETE_MODE_SOFT = "soft";

    private static final String TABLE_TASK_WORKING_MEMORY = "task_working_memory";
    private static final String TABLE_RELATIONAL_WORKING_MEMORY = "relational_working_memory";
    private static final String TABLE_TASK_SEMANTIC_FACT = "task_semantic_fact";
    private static final String TABLE_RELATIONAL_SEMANTIC_FACT = "relational_semantic_fact";
    private static final String TABLE_TASK_EPISODE = "task_episode";
    private static final String TABLE_RELATIONAL_EPISODE = "relational_episode";

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
            MemoryDomainEnum domain = normalizeDomain(memoryDomain);
            MemoryLayerEnum layer = normalizeLayer(memoryLayer);
            ToolActionEnum actionEnum = ToolActionEnum.getByCode(action).orElse(null);

            if (actionEnum == ToolActionEnum.INSERT) {
                if (content == null || content.isBlank()) {
                    return error("INSERT requires content");
                }
                if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.WORKING) {
                    return success(insertTaskWorkingMemory(sessionId, content));
                }
                if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.WORKING) {
                    return success(insertRelationalWorkingMemory(sessionId, content));
                }
                if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.SEMANTIC) {
                    return success(insertTaskSemanticFact(sessionId, normalize(factType, "DOMAIN_FACT"), normalize(factKey, "manual_fact"), content));
                }
                if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.SEMANTIC) {
                    return success(insertRelationalSemanticFact(sessionId, normalize(factType, "INTERACTION_STYLE"), normalize(factKey, "manual_relation_fact"), content));
                }
                if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.EPISODIC) {
                    return success(insertTaskEpisode(sessionId, content));
                }
                if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.EPISODIC) {
                    return success(insertRelationalEpisode(sessionId, content));
                }
                return error(ERROR_UNSUPPORTED_DOMAIN_LAYER);
            }

            if (actionEnum == ToolActionEnum.QUERY) {
                return success(queryMemory(domain, layer, sessionId));
            }

            if (actionEnum == ToolActionEnum.DELETE) {
                if (id == null) {
                    return error("DELETE requires id");
                }
                if (Boolean.TRUE.equals(hardDelete)) {
                    return success(deleteHard(domain, layer, id));
                }
                return success(deleteSoft(domain, layer, id));
            }

            return error(ERROR_UNKNOWN_ACTION + action);
        } catch (Exception e) {
            return error("operation failed: " + e.getMessage());
        }
    }

    private Map<String, Object> insertTaskWorkingMemory(String sessionId, String content) {
        toolMemoryMapper.upsertTaskWorkingMemory(normalizeSessionId(sessionId), content);
        return Map.of("table", TABLE_TASK_WORKING_MEMORY, "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertRelationalWorkingMemory(String sessionId, String content) {
        toolMemoryMapper.upsertRelationalWorkingMemory(normalizeSessionId(sessionId), content);
        return Map.of("table", TABLE_RELATIONAL_WORKING_MEMORY, "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertTaskSemanticFact(String sessionId, String factType, String factKey, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertTaskSemanticFact(normalizeSessionId(sessionId), factType, factKey, content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_TASK_SEMANTIC_FACT, "fact_type", factType, "fact_key", factKey);
    }

    private Map<String, Object> insertRelationalSemanticFact(String sessionId, String factType, String factKey, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertRelationalSemanticFact(normalizeSessionId(sessionId), factType, factKey, content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_RELATIONAL_SEMANTIC_FACT, "fact_type", factType, "fact_key", factKey);
    }

    private Map<String, Object> insertTaskEpisode(String sessionId, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertTaskEpisode(normalizeSessionId(sessionId), content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_TASK_EPISODE, "session_id", normalizeSessionId(sessionId));
    }

    private Map<String, Object> insertRelationalEpisode(String sessionId, String content) {
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertRelationalEpisode(normalizeSessionId(sessionId), content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_RELATIONAL_EPISODE, "session_id", normalizeSessionId(sessionId));
    }

    private List<Map<String, Object>> queryMemory(MemoryDomainEnum domain, MemoryLayerEnum layer, String sessionId) {
        String sid = normalizeSessionId(sessionId);
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.WORKING) {
            return toolMemoryMapper.queryTaskWorkingMemory(sid);
        }
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.WORKING) {
            return toolMemoryMapper.queryRelationalWorkingMemory(sid);
        }
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.SEMANTIC) {
            return toolMemoryMapper.queryTaskSemanticFacts(sid);
        }
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.SEMANTIC) {
            return toolMemoryMapper.queryRelationalSemanticFacts(sid);
        }
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.EPISODIC) {
            return toolMemoryMapper.queryTaskEpisodes(sid);
        }
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.EPISODIC) {
            return toolMemoryMapper.queryRelationalEpisodes(sid);
        }
        return Collections.emptyList();
    }

    private Map<String, Object> deleteHard(MemoryDomainEnum domain, MemoryLayerEnum layer, Long id) {
        String table = resolveTable(domain, layer);
        switch (table) {
            case TABLE_TASK_WORKING_MEMORY -> toolMemoryMapper.deleteTaskWorkingMemory(id);
            case TABLE_RELATIONAL_WORKING_MEMORY -> toolMemoryMapper.deleteRelationalWorkingMemory(id);
            case TABLE_TASK_SEMANTIC_FACT -> toolMemoryMapper.deleteTaskSemanticFact(id);
            case TABLE_RELATIONAL_SEMANTIC_FACT -> toolMemoryMapper.deleteRelationalSemanticFact(id);
            case TABLE_TASK_EPISODE -> toolMemoryMapper.deleteTaskEpisode(id);
            case TABLE_RELATIONAL_EPISODE -> toolMemoryMapper.deleteRelationalEpisode(id);
            default -> throw new IllegalArgumentException(ERROR_UNSUPPORTED_TABLE);
        }
        return Map.of("table", table, "id", id, "deleted", DELETE_MODE_HARD);
    }

    private Map<String, Object> deleteSoft(MemoryDomainEnum domain, MemoryLayerEnum layer, Long id) {
        String table = resolveTable(domain, layer);
        if (TABLE_TASK_SEMANTIC_FACT.equals(table) || TABLE_RELATIONAL_SEMANTIC_FACT.equals(table)) {
            if (TABLE_TASK_SEMANTIC_FACT.equals(table)) {
                toolMemoryMapper.softDeleteTaskSemanticFact(id);
            } else {
                toolMemoryMapper.softDeleteRelationalSemanticFact(id);
            }
            return Map.of("table", table, "id", id, "deleted", DELETE_MODE_SOFT);
        }
        return deleteHard(domain, layer, id);
    }

    private String resolveTable(MemoryDomainEnum domain, MemoryLayerEnum layer) {
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.WORKING) return TABLE_TASK_WORKING_MEMORY;
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.WORKING) return TABLE_RELATIONAL_WORKING_MEMORY;
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.SEMANTIC) return TABLE_TASK_SEMANTIC_FACT;
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.SEMANTIC) return TABLE_RELATIONAL_SEMANTIC_FACT;
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.EPISODIC) return TABLE_TASK_EPISODE;
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.EPISODIC) return TABLE_RELATIONAL_EPISODE;
        throw new IllegalArgumentException(ERROR_UNSUPPORTED_DOMAIN_LAYER);
    }

    private MemoryDomainEnum normalizeDomain(String value) {
        return MemoryDomainEnum.getByCode(value).orElse(MemoryDomainEnum.TASK);
    }

    private MemoryLayerEnum normalizeLayer(String value) {
        return MemoryLayerEnum.getByCode(value).orElse(MemoryLayerEnum.SEMANTIC);
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT);
    }

    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? SessionConstant.DEFAULT_SESSION_ID : sessionId;
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
