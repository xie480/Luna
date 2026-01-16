package org.yilena.runa.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yilena.runa.properties.OllamaProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/*
    Ollama API客户端
 */
@Component
@RequiredArgsConstructor
public class OllamaClient {

    private final OllamaProperty props;
    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /*
        同步生成
     */
    public String generateSync(String prompt) throws IOException, InterruptedException {
        // 构建请求体
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "prompt", prompt,
                "stream", false
        );
        // 构建请求
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/api/generate"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        // 发送请求
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        // 检查返回码
        if (resp.statusCode() / 100 != 2) {
            // 返回错误
            throw new IOException("Ollama returned " + resp.statusCode() + ": " + resp.body());
        }
        // 返回结果
        return resp.body();
    }
}
