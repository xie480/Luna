package org.yilena.luna.utils;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.EmbeddingProperty;
import org.yilena.luna.properties.GeminiProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 模型调用工具类
 * 已重构为基于 LangChain4j 实现，支持多模态及更优雅的 API 调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientUtil {

    private final GeminiProperty geminiProperty;
    private final EmbeddingProperty embeddingProperty;

    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        // 提取 Base URL (LangChain4j 期望的格式是不带 /chat/completions 的根路径)
        String baseUrl = geminiProperty.getUrl()
                .replace("/chat/completions", "")
                .replace("/embeddings", "");

        // 初始化全局复用的 Embedding 模型
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(geminiProperty.getApi())
                .modelName("text-embedding-004")
                .timeout(Duration.ofSeconds(30))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 统一的模型生成入口
     */
    public LlmResponse generate(LlmRequest request) {
        if (request.getModelType() == ModelType.OPENAI_COMPATIBLE) {
            return callOpenAiCompatible(request);
        }
        // 未来可在此处扩展 QWEN, OLLAMA 等其他原生 SDK 的调用逻辑
        throw new UnsupportedOperationException("暂不支持的模型类型: " + request.getModelType());
    }

    /**
     * 调用兼容 OpenAI 格式的接口（支持多模态）
     */
    private LlmResponse callOpenAiCompatible(LlmRequest request) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            for (LlmMessage msg : request.getMessages()) {
                if ("system".equalsIgnoreCase(msg.getRole())) {
                    messages.add(SystemMessage.from(msg.getText()));
                } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                    messages.add(AiMessage.from(msg.getText()));
                } else {
                    // 处理 User 角色，支持多模态（文本+图片）
                    if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                        List<Content> contents = new ArrayList<>();
                        if (msg.getText() != null && !msg.getText().isEmpty()) {
                            contents.add(TextContent.from(msg.getText()));
                        }
                        for (String imageUrl : msg.getImageUrls()) {
                            contents.add(ImageContent.from(imageUrl));
                        }
                        messages.add(UserMessage.from(contents));
                    } else {
                        messages.add(UserMessage.from(msg.getText() != null ? msg.getText() : ""));
                    }
                }
            }

            String baseUrl = geminiProperty.getUrl()
                    .replace("/chat/completions", "")
                    .replace("/embeddings", "");

            // 动态构建 ChatModel，以便支持每次请求不同的 temperature 和 modelName
            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(geminiProperty.getApi())
                    .modelName(request.getModelName())
                    .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            Response<AiMessage> response = chatModel.generate(messages);
            return LlmResponse.builder().content(response.content().text()).build();

        } catch (Exception e) {
            log.error("调用模型异常，model={}: {}", request.getModelName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取文本 Embedding
     * 改为实例方法以使用注入的配置
     */
    public String getEmbedding(String text) throws Exception {

        String pythonPath = embeddingProperty.getPythonPath();
        String scriptPath = embeddingProperty.getScriptPath();
        String modelPath = embeddingProperty.getModelPath();

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                modelPath, // 传递模型路径
                text       // 传递文本
        );

        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        String result = reader.readLine();

        process.waitFor();

        return result;
    }
}
