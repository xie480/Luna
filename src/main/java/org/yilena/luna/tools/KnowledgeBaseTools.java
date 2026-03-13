package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.SourceType;
import org.yilena.luna.service.KnowledgeBaseService;

import java.util.Arrays;

@Component
public class KnowledgeBaseTools extends BaseTool {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseTools(ObjectMapper objectMapper, KnowledgeBaseService knowledgeBaseService) {
        super(objectMapper);
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @LunaState(value = "Luna 正在查阅或整理本地知识库...", status = "KNOWLEDGE_BASE")
    @Tool("""
    【知识库(KnowledgeBase) CRUD 工具】
    目标实体类定义 (Schema):
    - id: Long (自动生成)
    - title: String (必填, 标题)
    - content: String (必填, 内容)
    - sourceType: String (必填, 来源类型, 如 TEXT, WEB, FILE)
    - sourcePath: String (选填, 来源路径)

    参数说明:
    - action: 必填。可选值: "INSERT", "QUERY" (注: 知识库涉及向量化，暂不支持直接 UPDATE/DELETE，请通过重新 INSERT 覆盖)
    - title, content, sourceType, sourcePath: INSERT 时提供。
    - query: QUERY 时提供的搜索词。
    """)
    @LunaLogRecord(module = "tool", action = "manage_knowledge", type = LogType.TOOL_CALL)
    public String manageKnowledgeBase(String action, String title, String content, String sourceType, String sourcePath, String query) {
        try {
            if ("INSERT".equalsIgnoreCase(action)) {
                if (title == null || content == null || sourceType == null) {
                    return error("INSERT 必须提供 title, content 和 sourceType");
                }
                SourceType st;
                try {
                    st = SourceType.valueOf(sourceType.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // 明确提示错误，而不是默默使用默认值
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
            return error("操作异常: " + e.getMessage());
        }
    }
}
