package org.yilena.luna.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
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
     */
    public interface LunaToolAgent {
        String chat(String prompt);
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
                .timeout(Duration.ofSeconds(1200)) // 工具调用可能耗时较长，增加超时时间
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
