package org.yilena.luna.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.luna.properties.OllamaProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Ollama 客户端，负责向本地 Ollama 服务发起同步生成请求。
 */
@Component
@RequiredArgsConstructor
public class OllamaClient {

    /**
     * Ollama 连接配置。
     */
    private final OllamaProperty props;

    /**
     * JSON 序列化工具，用于构造请求体。
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 复用的 HTTP 客户端。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * 执行同步文本生成，并返回 Ollama 原始响应内容。
     */
    public String generateSync(String prompt) throws IOException, InterruptedException {
        /**
         * 先组装模型、提示词和流式开关，构造符合 Ollama 协议的请求体。
         */
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "prompt", prompt,
                "stream", false
        );

        /**
         * 将请求体序列化为 JSON，并构造带超时和内容类型的 HTTP 请求。
         */
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/api/generate"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        /**
         * 发起同步调用后校验 HTTP 状态码，非 2xx 响应直接抛出异常供上层处理。
         */
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Ollama returned " + resp.statusCode() + ": " + resp.body());
        }

        /**
         * 成功时直接返回 Ollama 原始响应，由上层决定如何解析。
         */
        return resp.body();
    }
}
