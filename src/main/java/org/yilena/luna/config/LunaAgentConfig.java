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
            你是一个专门负责调用工具的后台路由 Agent。
            你的唯一职责是：分析用户的输入，判断是否需要调用工具（Function Calling）来获取外部数据。
            
            【核心规则】
            1. 如果用户输入包含 "搜索"、"新闻"、"天气"、"查一下" 等需要外部信息的指令，你**必须**调用对应的工具。
            2. 绝对不要自己编造答案，必须通过工具获取真实数据。
            3. 如果用户的输入只是纯粹的闲聊、打招呼，且完全不需要调用任何工具，请直接且仅回复："NO_ACTION_NEEDED"。
            4. 当工具调用成功并返回数据后，请将数据总结成一段客观、简练的纯文本返回。
            5. **重要**：请务必结合【历史对话上下文】来理解用户当前输入的真实意图（例如代词指代、省略句）。如果用户说“试一下”、“继续”等，请根据上文判断具体要执行什么操作。
            
            【异常处理强制规则】
            1. **严禁返回空内容**。
            2. 如果工具调用失败、超时或未返回数据，你必须明确返回："【工具执行失败】原因：..."。
            3. 如果你调用了工具但工具返回了空结果，你必须明确返回："【无结果】工具已执行但未找到相关信息"。
            """)
        @UserMessage("历史对话上下文:\n{{chatHistory}}\n\n本地知识库参考:\n{{ragContext}}\n\n用户当前输入: {{userInput}}")
        String route(@V("userInput") String userInput, @V("ragContext") String ragContext, @V("chatHistory") String chatHistory);
    }

    @Bean
    public LunaToolRouter lunaToolRouter() {
        // 获取 flash 模型的配置 (Flash 模型通常响应更快且支持多模态，适合做 Router)
        GeminiProperty.ModelConfig toolConfig = geminiProperty.getFlash();
        
        // 提取 baseUrl，LangChain4j 的 OpenAiChatModel 期望的 baseUrl 是到 /v1 為止
        String baseUrl = toolConfig.getUrl();
        if (baseUrl != null && baseUrl.endsWith("/chat/completions")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }

        // 使用 yaml 中的 flashModel 初始化支持 Tool Calling 的 ChatLanguageModel
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(toolConfig.getApiKey())
                .modelName(toolConfig.getModelName()) // 采用 flashModel 處理工具
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

        log.info("LunaToolRouter 初始化完成，已註冊工具並使用模型: {}", toolConfig.getModelName());
        return router;
    }
}
