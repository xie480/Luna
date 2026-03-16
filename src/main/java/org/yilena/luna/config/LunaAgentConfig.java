package org.yilena.luna.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
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
     * 定義 LangChain4j Agent 接口
     * 使用 @UserMessage 和 @V 註解，確保傳入的 prompt 被當作純文本變量安全注入，
     * 徹底避免 LangChain4j 解析 prompt 內部的 {{...}} 導致變量缺失異常。
     */
    public interface LunaToolAgent {
        @UserMessage("{{prompt}}")
        String chat(@V("prompt") String prompt);
    }

    @Bean
    public LunaToolAgent lunaToolAgent() {
        // 提取 baseUrl，LangChain4j 的 OpenAiChatModel 期望的 baseUrl 是到 /v1 為止
        String baseUrl = geminiProperty.getUrl();
        if (baseUrl != null && baseUrl.endsWith("/chat/completions")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }

        // 使用 yaml 中的 midModel 初始化支持 Tool Calling 的 ChatLanguageModel
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(geminiProperty.getApi())
                .modelName(geminiProperty.getMidModelName()) // 采用 midModel
                .timeout(Duration.ofSeconds(120)) // 縮短超時時間，防止被反向代理(如Nginx/Cloudflare)強制掐斷連接
                .maxRetries(3) // 增加重試機制應對網絡抖動
                .logRequests(true) // 開啟請求日誌，方便排查網絡問題
                .logResponses(true) // 開啟響應日誌
                .build();

        // 构建 AiServices 代理，并注册所有工具
        LunaToolAgent agent = AiServices.builder(LunaToolAgent.class)
                .chatLanguageModel(chatModel)
                .tools(searchTools, knowledgeBaseTools, memoryTools, scheduleTools, logTools, preferenceTools)
                .build();

        log.info("LunaToolAgent 初始化完成，已註冊工具並使用模型: {}", geminiProperty.getMidModelName());
        return agent;
    }
}
