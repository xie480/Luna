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
/**
 * 知识库工具类，负责对外暴露知识写入与检索能力，供智能体在对话过程中管理长期知识内容。
 */
public class KnowledgeBaseTools extends BaseTool {

    /**
     * 知识检索默认返回条数，用于限制单次工具查询结果规模。
     */
    private static final int QUERY_TOP_K = 5;

    /**
     * 知识库服务，用于执行知识新增和向量检索等核心能力。
     */
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseTools(ObjectMapper objectMapper, KnowledgeBaseService knowledgeBaseService) {
        super(objectMapper);
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @LunaState(value = LunaStateConstant.VALUE_KNOWLEDGE_BASE, status = LunaStateConstant.STATUS_KNOWLEDGE_BASE)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_KNOWLEDGE, type = LogType.TOOL_CALL, content = "管理知识库")
    /**
     * 统一处理知识库写入与查询请求，根据动作类型分发到对应知识管理流程。
     */
    public String manageKnowledgeBase(
            @RequestParam("action") String action,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "sourcePath", required = false) String sourcePath,
            @RequestParam(value = "query", required = false) String query) {
        try {
            /**
             * 先解析工具动作，非法动作直接拦截，避免进入错误的知识处理分支。
             */
            ToolActionEnum actionEnum = ToolActionEnum.getByCode(action).orElse(null);
            if (actionEnum == null) {
                return error("未知的 action: " + action + "，知识库暂仅支持 INSERT 和 QUERY");
            }
            /**
             * 写入分支负责校验入参并新增知识，使内容可以进入后续检索范围。
             */
            if (actionEnum == ToolActionEnum.INSERT) {
                if (title == null || content == null || sourceType == null) {
                    return error("INSERT 必须提供 title, content 和 sourceType");
                }
                SourceType st = parseSourceType(sourceType);
                if (st == null) {
                    return error("无效的 sourceType: " + sourceType + "。可选值: " + Arrays.toString(SourceType.values()));
                }
                /**
                 * 来源类型校验通过后调用知识库服务完成写入，使新增内容可以参与后续检索。
                 */
                knowledgeBaseService.addKnowledge(title, content, st, sourcePath);
                return success("知识库写入成功");
            }
            /**
             * 查询分支要求提供检索词，再按固定数量返回最相关的知识结果。
             */
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

    /**
     * 解析知识来源类型，兼容来源编码和值两种输入形式。
     */
    private SourceType parseSourceType(String raw) {
        return SourceType.fromCodeOrValue(raw).orElse(null);
    }
}
