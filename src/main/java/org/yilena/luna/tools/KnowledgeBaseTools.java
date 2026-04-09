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
import org.yilena.luna.enums.ToolActionEnum;
import org.yilena.luna.service.KnowledgeBaseService;

import java.util.Arrays;

@Slf4j
@Component
public class KnowledgeBaseTools extends BaseTool {

    private static final int QUERY_TOP_K = 5;

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
            ToolActionEnum actionEnum = ToolActionEnum.getByCode(action).orElse(null);
            if (actionEnum == null) {
                return error("未知的 action: " + action + "，知识库暂仅支持 INSERT 和 QUERY");
            }
            if (actionEnum == ToolActionEnum.INSERT) {
                if (title == null || content == null || sourceType == null) {
                    return error("INSERT 必须提供 title, content 和 sourceType");
                }
                SourceType st = parseSourceType(sourceType);
                if (st == null) {
                    return error("无效的 sourceType: " + sourceType + "。可选值: " + Arrays.toString(SourceType.values()));
                }
                knowledgeBaseService.addKnowledge(title, content, st, sourcePath);
                return success("知识库写入成功");
            }
            if (actionEnum == ToolActionEnum.QUERY) {
                if (query == null) {
                    return error("QUERY 必须提供 query");
                }
                return success(knowledgeBaseService.searchKnowledge(query, QUERY_TOP_K));
            }
            return error("未知的 action: " + action + "，知识库暂仅支持 INSERT 和 QUERY");
        } catch (Exception e) {
            log.error("KnowledgeBaseTools 执行异常", e);
            return error("操作异常: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private SourceType parseSourceType(String raw) {
        return SourceType.fromCodeOrValue(raw).orElse(null);
    }
}
