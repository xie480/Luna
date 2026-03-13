package org.yilena.luna.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.properties.GeminiProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * LLM 模型调用工具类
 * 负责处理 HTTP 连接池、多模态参数封装及不同模型的路由
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientUtil {

    private final GeminiProperty geminiProperty;
    private final ObjectMapper mapper;

    // 共享的 HttpClient，提高性能，避免频繁创建连接
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
            List<Map<String, Object>> messages = new ArrayList<>();
            for (LlmMessage msg : request.getMessages()) {
                Map<String, Object> messageMap = new LinkedHashMap<>();
                messageMap.put("role", msg.getRole());

                if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                    // 多模态格式：包含文本和图片
                    List<Map<String, Object>> contentList = new ArrayList<>();
                    if (msg.getText() != null && !msg.getText().isEmpty()) {
                        contentList.add(Map.of("type", "text", "text", msg.getText()));
                    }
                    for (String imageUrl : msg.getImageUrls()) {
                        contentList.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
                    }
                    messageMap.put("content", contentList);
                } else {
                    // 纯文本格式
                    messageMap.put("content", msg.getText() != null ? msg.getText() : "");
                }
                messages.add(messageMap);
            }

            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("model", request.getModelName());
            bodyMap.put("messages", messages);
            if (request.getTemperature() != null) {
                bodyMap.put("temperature", request.getTemperature());
            }

            String requestBody = mapper.writeValueAsString(bodyMap);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(geminiProperty.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + geminiProperty.getApi())
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("模型调用失败，model={}，statusCode={}，body={}", request.getModelName(), response.statusCode(), response.body());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                log.error("响应中未找到内容，model={}，body={}", request.getModelName(), response.body());
                return null;
            }

            return LlmResponse.builder().content(contentNode.asText()).build();

        } catch (Exception e) {
            log.error("调用模型异常，model={}: {}", request.getModelName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取文本的 Embedding 向量 (用于 RAG 知识库)
     * 默认调用 OpenAI 兼容的 /embeddings 接口
     *
     * @param text 需要向量化的文本
     * @return 浮点数向量列表
     */
    public List<Double> getEmbedding(String text) {
        try {
            // 简单的 URL 替换逻辑，假设配置的是 chat/completions 结尾
            String embedUrl = geminiProperty.getUrl().replace("/chat/completions", "/embeddings");
            // 如果替换后没有变化（说明原 URL 不是以 chat/completions 结尾），可能需要根据实际情况调整
            // 这里暂且假设用户配置的是标准 OpenAI 兼容路径

            Map<String, Object> bodyMap = new LinkedHashMap<>();
            // 使用 text-embedding-004 模型，这是 Gemini 系列常用的 embedding 模型
            bodyMap.put("model", "text-embedding-004");
            bodyMap.put("input", text);

            String requestBody = mapper.writeValueAsString(bodyMap);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(embedUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + geminiProperty.getApi())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding 调用失败，statusCode={}，body={}", response.statusCode(), response.body());
                return Collections.emptyList();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");

            if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
                log.error("Embedding 响应格式错误，body={}", response.body());
                return Collections.emptyList();
            }

            List<Double> vector = new ArrayList<>();
            for (JsonNode node : embeddingNode) {
                vector.add(node.asDouble());
            }
            return vector;

        } catch (Exception e) {
            log.error("获取 Embedding 异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
