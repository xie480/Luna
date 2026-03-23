package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.service.KnowledgeBaseService;

import java.util.Arrays;

@Slf4j
@Component
public class KnowledgeBaseTools extends BaseTool {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseTools(ObjectMapper objectMapper, KnowledgeBaseService knowledgeBaseService) {
        super(objectMapper);
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @LunaState(value = LunaStateConstant.VALUE_KNOWLEDGE_BASE, status = LunaStateConstant.STATUS_KNOWLEDGE_BASE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_KNOWLEDGE, type = LogType.TOOL_CALL, content = "管理知识库")
    public String manageKnowledgeBase(
            @RequestParam("action") String action,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "sourcePath", required = false) String sourcePath,
            @RequestParam(value = "query", required = false) String query) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (title == null || content == null || sourceType == null) {
                    return error("INSERT 必须提供 title, content 和 sourceType");
                }
                SourceType st = parseSourceType(sourceType);
                if (st == null) {
                    return error("无效的 sourceType: " + sourceType + "。可选值: " + Arrays.toString(SourceType.values()));
                }

                knowledgeBaseService.addKnowledge(title, content, st, sourcePath);
                return success("知识库写入成功");
            } else if ("QUERY".equalsIgnoreCase(action)) {
                if (query == null) return error("QUERY 必须提供 query");
                return success(knowledgeBaseService.searchKnowledge(query, 5));
            }
            return error("未知的 action: " + action + "，知识库暂仅支持 INSERT 和 QUERY");
        } catch (Exception e) {
            log.error("KnowledgeBaseTools 执行异常", e);
            return error("操作异常: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * 兼容解析 SourceType：
     * - 新格式：FILE / WEB_SEARCH / MANUAL_INPUT
     * - 舊格式：0 / 1 / 2
     */
    private SourceType parseSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return SourceType.valueOf(v.toUpperCase());
        } catch (Exception ignore) {
            if ("0".equals(v)) return SourceType.FILE;
            if ("1".equals(v)) return SourceType.WEB_SEARCH;
            if ("2".equals(v)) return SourceType.MANUAL_INPUT;
            return null;
        }
    }
}
