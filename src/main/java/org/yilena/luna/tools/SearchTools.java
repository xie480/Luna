package org.yilena.luna.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.enums.LogType;

@Slf4j
@Component
public class SearchTools extends BaseTool {

    public SearchTools(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    /**
     * 联网搜索工具
     */
    @Tool("当你需要回答的问题超出了你的知识范围，或者需要获取实时信息（如新闻、天气、股价）时，调用此工具进行联网搜索。返回格式为 JSON。")
    @LunaLogRecord(module = "tool", action = "search_web", type = LogType.TOOL_CALL)
    public String searchWeb(String query) {
        log.info("Luna 正在执行联网搜索，关键词: {}", query);
        // TODO: 对接真实的搜索引擎 API
        String result;
        if (query.contains("天气")) {
            result = "【搜索结果】: 今天天气晴朗，气温 25 度，适合外出。";
        } else if (query.contains("新闻")) {
            result = "【搜索结果】: 最新科技新闻显示，AI Agent 技术正在快速发展。";
        } else {
            result = "【搜索结果】: 关于 \"" + query + "\" 的网络搜索暂未返回具体内容，请尝试更换关键词或告知用户无法获取实时信息。";
        }
        return success(result);
    }
}
