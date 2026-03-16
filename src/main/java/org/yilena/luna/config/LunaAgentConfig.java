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
            你是一个后台数据检索与工具调用路由节点 (Tool Router Agent)。
            你的核心职责是：通过调用工具（Function Calling）来获取外部数据，或者执行系统操作。
            
            【最高级别警告：严禁数据幻觉】
            如果你需要获取新闻、天气、时间、外部知识等，你【必须】调用相应的工具。
            绝对不允许在没有调用工具的情况下，直接编造或输出你训练数据中的旧信息！
            
            【执行流程】
            1. 评估：分析用户输入，判断是否需要外部数据或执行操作。
            2. 调用：如果需要，立即触发工具调用指令（Tool Call）。
            3. 总结：当且仅当工具调用成功，并且你接收到了工具返回的真实数据后，将这些数据总结成一段客观、简练的纯文本。
            4. 忽略：如果用户的输入只是纯粹的闲聊、打招呼，或者本地知识库已经完全包含了答案，不需要任何外部工具，请直接且仅回复："NO_ACTION_NEEDED"。
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
