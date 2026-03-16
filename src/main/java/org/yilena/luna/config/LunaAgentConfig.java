package org.yilena.luna.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yilena.luna.properties.GeminiProperty;
import org.yilena.luna.tools.*;

import java.time.Duration;

/**
 * LangChain4j Agent 配置類
 * 負責組裝 Model、Tools 並生成 Agent Bean
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LunaAgentConfig {

    private final GeminiProperty geminiProperty;

    // 注入所有的 Tools
    private final SearchTools searchTools;
    private final KnowledgeBaseTools knowledgeBaseTools;
    private final MemoryTools memoryTools;
    private final ScheduleTools scheduleTools;
    private final LogTools logTools;
    private final PreferenceTools preferenceTools;

    /**
     * 路由 Agent：只負責判斷和調用工具，不負責扮演角色
     */
    public interface LunaToolRouter {
        @SystemMessage("""
            你是一个后台数据检索与工具调用路由节点。
            你的唯一任务是分析用户的输入，判断是否需要调用提供的工具（如联网搜索、查询数据库、管理日程等）。
            
            规则：
            1. 如果需要调用工具，请立即调用。获取结果后，请用客观、简练的语言总结你查到的数据。
            2. 如果用户的输入只是普通的闲聊、问候，或者根据提供的本地知识库已经足够回答，【绝对不要】调用任何工具。
            3. 如果不需要调用工具，请直接且仅回复这几个大写字母："NO_ACTION_NEEDED"。
            4. 绝对不要模仿任何角色语气，不要输出 JSON，只输出客观数据总结或 "NO_ACTION_NEEDED"。
            """)
        @UserMessage("用户输入: {{userInput}}\n本地知识库参考: {{ragContext}}")
        String route(@V("userInput") String userInput, @V("ragContext") String ragContext);
    }

    @Bean
    public LunaToolRouter lunaToolRouter() {
        // 提取 baseUrl，LangChain4j 的 OpenAiChatModel 期望的 baseUrl 是到 /v1 為止
        String baseUrl = geminiProperty.getUrl();
        if (baseUrl != null && baseUrl.endsWith("/chat/completions")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }

        // 使用 yaml 中的 midModel 初始化支持 Tool Calling 的 ChatLanguageModel
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(geminiProperty.getApi())
                .modelName(geminiProperty.getMidModelName()) // 采用 midModel 處理工具
                .timeout(Duration.ofSeconds(120)) // 縮短超時時間，防止被反向代理強制掐斷連接
                .maxRetries(3) // 增加重試機制應對網絡抖動
                .logRequests(true) // 開啟請求日誌，方便排查網絡問題
                .logResponses(true) // 開啟響應日誌
                .build();

        // 构建 AiServices 代理，并注册所有工具
        LunaToolRouter router = AiServices.builder(LunaToolRouter.class)
                .chatLanguageModel(chatModel)
                .tools(searchTools, knowledgeBaseTools, memoryTools, scheduleTools, logTools, preferenceTools)
                .build();

        log.info("LunaToolRouter 初始化完成，已註冊工具並使用模型: {}", geminiProperty.getMidModelName());
        return router;
    }
}
