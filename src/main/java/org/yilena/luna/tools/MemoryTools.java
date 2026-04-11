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
/**
 * 记忆工具类，负责对任务域和关系域的工作记忆、语义记忆与情节记忆执行增删查操作。
 */
public class MemoryTools extends BaseTool {

    /**
     * 域与层级组合不受支持时返回的统一错误信息。
     */
    private static final String ERROR_UNSUPPORTED_DOMAIN_LAYER = "unsupported domain/layer";
    /**
     * 无法映射到具体存储表时返回的统一错误信息。
     */
    private static final String ERROR_UNSUPPORTED_TABLE = "unsupported table";
    /**
     * 工具动作无法识别时返回的错误前缀。
     */
    private static final String ERROR_UNKNOWN_ACTION = "unknown action: ";
    /**
     * 硬删除标识，用于表示数据已从底层表中物理移除。
     */
    private static final String DELETE_MODE_HARD = "hard";
    /**
     * 软删除标识，用于表示数据仅被逻辑标记为不可用。
     */
    private static final String DELETE_MODE_SOFT = "soft";

    /**
     * 任务域工作记忆对应的数据表名。
     */
    private static final String TABLE_TASK_WORKING_MEMORY = "task_working_memory";
    /**
     * 关系域工作记忆对应的数据表名。
     */
    private static final String TABLE_RELATIONAL_WORKING_MEMORY = "relational_working_memory";
    /**
     * 任务域语义事实对应的数据表名。
     */
    private static final String TABLE_TASK_SEMANTIC_FACT = "task_semantic_fact";
    /**
     * 关系域语义事实对应的数据表名。
     */
    private static final String TABLE_RELATIONAL_SEMANTIC_FACT = "relational_semantic_fact";
    /**
     * 任务域情节记忆对应的数据表名。
     */
    private static final String TABLE_TASK_EPISODE = "task_episode";
    /**
     * 关系域情节记忆对应的数据表名。
     */
    private static final String TABLE_RELATIONAL_EPISODE = "relational_episode";

    /**
     * 记忆工具专用 Mapper，用于直接操作不同层级记忆数据表。
     */
    private final ToolMemoryMapper toolMemoryMapper;
    /**
     * 大模型客户端工具，用于为语义记忆和情节记忆生成向量表示。
     */
    private final LlmClientUtil llmClientUtil;

    public MemoryTools(ObjectMapper objectMapper, ToolMemoryMapper toolMemoryMapper, LlmClientUtil llmClientUtil) {
        super(objectMapper);
        this.toolMemoryMapper = toolMemoryMapper;
        this.llmClientUtil = llmClientUtil;
    }

    @LunaState(value = LunaStateConstant.VALUE_MEMORY, status = LunaStateConstant.STATUS_MEMORY)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_MEMORY, type = LogType.TOOL_CALL, content = "manage memory v2")
    /**
     * 统一处理记忆的新增、查询和删除请求，按记忆域与层级路由到对应存储逻辑。
     */
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
            /**
             * 先标准化记忆域、记忆层和动作类型，确保后续分支判断基于统一枚举值。
             */
            MemoryDomainEnum domain = normalizeDomain(memoryDomain);
            MemoryLayerEnum layer = normalizeLayer(memoryLayer);
            ToolActionEnum actionEnum = ToolActionEnum.getByCode(action).orElse(null);

            /**
             * 新增记忆前先校验核心内容，再按域和层级分发到对应入库流程。
             */
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

            /**
             * 查询流程直接按域、层和会话范围读取当前记忆视图，供模型继续消费。
             */
            if (actionEnum == ToolActionEnum.QUERY) {
                return success(queryMemory(domain, layer, sessionId));
            }

            /**
             * 删除流程要求明确记录主键，再根据删除模式决定逻辑删除或物理删除。
             */
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

    /**
     * 写入任务域工作记忆，并返回本次写入命中的存储表与会话标识。
     */
    private Map<String, Object> insertTaskWorkingMemory(String sessionId, String content) {
        toolMemoryMapper.upsertTaskWorkingMemory(normalizeSessionId(sessionId), content);
        return Map.of("table", TABLE_TASK_WORKING_MEMORY, "session_id", normalizeSessionId(sessionId));
    }

    /**
     * 写入关系域工作记忆，并维持同一会话下的关系态上下文。
     */
    private Map<String, Object> insertRelationalWorkingMemory(String sessionId, String content) {
        toolMemoryMapper.upsertRelationalWorkingMemory(normalizeSessionId(sessionId), content);
        return Map.of("table", TABLE_RELATIONAL_WORKING_MEMORY, "session_id", normalizeSessionId(sessionId));
    }

    /**
     * 写入任务域语义事实，同时补充向量数据以支持后续语义检索。
     */
    private Map<String, Object> insertTaskSemanticFact(String sessionId, String factType, String factKey, String content) {
        /**
         * 先为事实内容生成向量，再将结构化元信息和向量一起落库，便于后续召回。
         */
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertTaskSemanticFact(normalizeSessionId(sessionId), factType, factKey, content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_TASK_SEMANTIC_FACT, "fact_type", factType, "fact_key", factKey);
    }

    /**
     * 写入关系域语义事实，同时保留关系类事实的检索向量。
     */
    private Map<String, Object> insertRelationalSemanticFact(String sessionId, String factType, String factKey, String content) {
        /**
         * 为关系事实生成 embedding，可用于偏好和交互风格等内容的相似召回。
         */
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertRelationalSemanticFact(normalizeSessionId(sessionId), factType, factKey, content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_RELATIONAL_SEMANTIC_FACT, "fact_type", factType, "fact_key", factKey);
    }

    /**
     * 写入任务域情节记忆，并为后续情节检索保留向量表示。
     */
    private Map<String, Object> insertTaskEpisode(String sessionId, String content) {
        /**
         * 情节记忆以文本和向量同时存储，便于后续按时间或语义回放任务过程。
         */
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertTaskEpisode(normalizeSessionId(sessionId), content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_TASK_EPISODE, "session_id", normalizeSessionId(sessionId));
    }

    /**
     * 写入关系域情节记忆，用于沉淀用户交互过程中的阶段性事件。
     */
    private Map<String, Object> insertRelationalEpisode(String sessionId, String content) {
        /**
         * 为关系事件生成向量后落库，便于后续从历史互动中召回相似片段。
         */
        String embedding = safeEmbedding(content);
        toolMemoryMapper.insertRelationalEpisode(normalizeSessionId(sessionId), content, normalizeEmbedding(embedding));
        return Map.of("table", TABLE_RELATIONAL_EPISODE, "session_id", normalizeSessionId(sessionId));
    }

    /**
     * 按记忆域、层级和会话范围读取当前可见记忆内容。
     */
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

    /**
     * 执行物理删除，直接从目标记忆表中移除指定记录。
     */
    private Map<String, Object> deleteHard(MemoryDomainEnum domain, MemoryLayerEnum layer, Long id) {
        /**
         * 先解析出目标数据表，再调用对应 Mapper 方法完成真实删除。
         */
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

    /**
     * 执行逻辑删除，仅对支持软删除的语义事实表做状态标记。
     */
    private Map<String, Object> deleteSoft(MemoryDomainEnum domain, MemoryLayerEnum layer, Long id) {
        String table = resolveTable(domain, layer);
        /**
         * 语义事实保留历史痕迹时走软删除，其余表回退为物理删除以保证数据一致性。
         */
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

    /**
     * 根据记忆域和层级解析底层目标表名，作为增删操作的统一路由依据。
     */
    private String resolveTable(MemoryDomainEnum domain, MemoryLayerEnum layer) {
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.WORKING) return TABLE_TASK_WORKING_MEMORY;
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.WORKING) return TABLE_RELATIONAL_WORKING_MEMORY;
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.SEMANTIC) return TABLE_TASK_SEMANTIC_FACT;
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.SEMANTIC) return TABLE_RELATIONAL_SEMANTIC_FACT;
        if (domain == MemoryDomainEnum.TASK && layer == MemoryLayerEnum.EPISODIC) return TABLE_TASK_EPISODE;
        if (domain == MemoryDomainEnum.RELATION && layer == MemoryLayerEnum.EPISODIC) return TABLE_RELATIONAL_EPISODE;
        throw new IllegalArgumentException(ERROR_UNSUPPORTED_DOMAIN_LAYER);
    }

    /**
     * 将外部传入的记忆域规范化，默认落到任务域。
     */
    private MemoryDomainEnum normalizeDomain(String value) {
        return MemoryDomainEnum.getByCode(value).orElse(MemoryDomainEnum.TASK);
    }

    /**
     * 将外部传入的记忆层规范化，默认落到语义记忆层。
     */
    private MemoryLayerEnum normalizeLayer(String value) {
        return MemoryLayerEnum.getByCode(value).orElse(MemoryLayerEnum.SEMANTIC);
    }

    /**
     * 将可选文本参数规范化为大写值，缺省时使用预设兜底项。
     */
    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT);
    }

    /**
     * 规范化会话标识，缺失时回退到系统默认会话。
     */
    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? SessionConstant.DEFAULT_SESSION_ID : sessionId;
    }

    /**
     * 对 embedding 结果做兜底处理，避免下游数据库字段出现空向量。
     */
    private String normalizeEmbedding(String embedding) {
        if (embedding == null || embedding.isBlank() || "[]".equals(embedding.trim())) {
            return "[" + "0,".repeat(767) + "0]";
        }
        return embedding;
    }

    /**
     * 安全获取文本向量，向量服务异常时返回空值以避免工具流程中断。
     */
    private String safeEmbedding(String content) {
        try {
            return llmClientUtil.getEmbedding(content);
        } catch (Exception ignore) {
            return null;
        }
    }
}
