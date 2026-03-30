package org.yilena.luna.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.ModelType;
import org.yilena.luna.llm.LlmMessage;
import org.yilena.luna.llm.LlmRequest;
import org.yilena.luna.llm.LlmResponse;
import org.yilena.luna.prompt.PromptTemplates;
import org.yilena.luna.properties.EmbeddingProperty;
import org.yilena.luna.properties.GeminiProperty;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * LlmClientUtil ??
 */
public class LlmClientUtil {

    private final GeminiProperty geminiProperty; // 声明成员字段
    private final EmbeddingProperty embeddingProperty; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段

    @Value("${rerank.model-path:}") // 声明注解
    private String rerankModelPath; // 声明成员字段

    @Value("${rerank.script-path:./python/rerank.py}") // 声明注解
    private String rerankScriptPath; // 声明成员字段

    @Value("${inference.http.enabled:true}") // 声明注解
    private boolean inferenceHttpEnabled; // 声明成员字段

    @Value("${inference.http.embedding-url:http://127.0.0.1:18080/embedding}") // 声明注解
    private String embeddingServiceUrl; // 声明成员字段

    @Value("${inference.http.rerank-url:http://127.0.0.1:18081/rerank}") // 声明注解
    private String rerankServiceUrl; // 声明成员字段

    @Value("${inference.http.timeout-ms:1500}") // 声明注解
    private long inferenceHttpTimeoutMs; // 声明成员字段

    @Value("${inference.http.fallback-local:true}") // 声明注解
    private boolean fallbackLocal; // 声明成员字段

    private static final Map<String, String> scriptPathCache = new ConcurrentHashMap<>(); // 定义方法签名
    private static final Map<String, String> embeddingCache = new ConcurrentHashMap<>(); // 定义方法签名

    private final OkHttpClient httpClient = new OkHttpClient.Builder() // 定义方法签名
            .connectTimeout(Duration.ofMillis(1500)) // 执行当前逻辑
            .readTimeout(Duration.ofMillis(1500)) // 执行当前逻辑
            .writeTimeout(Duration.ofMillis(1500)) // 执行当前逻辑
            .build(); // 执行语句逻辑

    public LlmResponse generate(LlmRequest request) { // 定义方法签名
        if (request == null) { // 进行条件判断
            log.error("generate 调用失败：request 为空"); // 执行语句逻辑
            return null; // 返回处理结果
        } // 结束当前代码块
        log.info("开始调用 LLM，modelType={}, modelName={}", request.getModelType(), request.getModelName()); // 执行赋值操作

        if (request.getModelType() == ModelType.OPENAI_COMPATIBLE) { // 进行条件判断
            return callOpenAiCompatible(request); // 返回处理结果
        } // 结束当前代码块

        log.error("暂不支持的模型类型: {}", request.getModelType()); // 执行语句逻辑
        throw new UnsupportedOperationException("暂不支持的模型类型: " + request.getModelType()); // 抛出异常信息
    } // 结束当前代码块

    private LlmResponse callOpenAiCompatible(LlmRequest request) { // 定义方法签名
        try { // 尝试执行核心逻辑
            String userLatestText = extractLatestUserTextForSafetyCheck(request.getMessages()); // 执行赋值操作
            boolean enablePromptInjectionCheck = request.getEnablePromptInjectionCheck() == null || request.getEnablePromptInjectionCheck(); // 执行赋值操作

            if (enablePromptInjectionCheck && userLatestText != null && !userLatestText.isEmpty()) { // 进行条件判断
                boolean isSafe = isInputSafe(userLatestText); // 执行赋值操作
                if (!isSafe) { // 进行条件判断
                    log.warn("检测到潜在 Prompt Injection，已拦截，输入长度={}", userLatestText.length()); // 执行赋值操作
                    return LlmResponse.builder() // 返回处理结果
                            .content("由于触发了安全过滤，我无法完成此请求。") // 执行当前逻辑
                            .build(); // 执行语句逻辑
                } // 结束当前代码块
            } else { // 切换到分支逻辑
                log.debug("本次跳过 Prompt Injection 检测，enableCheck={}, hasUserText={}", // 执行赋值操作
                        enablePromptInjectionCheck, userLatestText != null && !userLatestText.isEmpty()); // 执行赋值操作
            } // 结束当前代码块

            List<ChatMessage> messages = new ArrayList<>(); // 执行赋值操作
            if (request.getMessages() != null) { // 进行条件判断
                for (LlmMessage msg : request.getMessages()) { // 执行循环处理
                    if ("system".equalsIgnoreCase(msg.getRole())) { // 进行条件判断
                        String hardenedSystemPrompt = msg.getText() + PromptTemplates.SYSTEM_SECURITY_NOTICE; // 执行赋值操作
                        messages.add(SystemMessage.from(hardenedSystemPrompt)); // 执行语句逻辑
                    } else if ("assistant".equalsIgnoreCase(msg.getRole())) { // 切换到分支逻辑
                        messages.add(AiMessage.from(msg.getText())); // 执行语句逻辑
                    } else { // 切换到分支逻辑
                        String safeText = msg.getText() != null ? msg.getText() : ""; // 执行赋值操作
                        String wrappedText = "<user_input>\n" + safeText + "\n</user_input>"; // 执行赋值操作

                        if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) { // 进行条件判断
                            List<Content> contents = new ArrayList<>(); // 执行赋值操作
                            if (!safeText.isEmpty()) { // 进行条件判断
                                contents.add(TextContent.from(wrappedText)); // 执行语句逻辑
                            } // 结束当前代码块
                            for (String imageUrl : msg.getImageUrls()) { // 执行循环处理
                                contents.add(ImageContent.from(imageUrl)); // 执行语句逻辑
                            } // 结束当前代码块
                            messages.add(UserMessage.from(contents)); // 执行语句逻辑
                        } else { // 切换到分支逻辑
                            messages.add(UserMessage.from(wrappedText)); // 执行语句逻辑
                        } // 结束当前代码块
                    } // 结束当前代码块
                } // 结束当前代码块
            } // 结束当前代码块

            String requestModelName = request.getModelName(); // 执行赋值操作
            GeminiProperty.ModelConfig config = getModelConfig(requestModelName); // 执行赋值操作

            if (config == null) { // 进行条件判断
                log.error("未找到模型 [{}] 对应配置，请检查 application.yaml", requestModelName); // 执行语句逻辑
                return null; // 返回处理结果
            } // 结束当前代码块

            String responseText = executeChatCall(messages, config, request.getTemperature()); // 执行赋值操作
            log.info("LLM 调用完成，model={}, responseLength={}", // 执行赋值操作
                    config.getModelName(), responseText != null ? responseText.length() : 0); // 执行赋值操作
            return LlmResponse.builder().content(responseText).build(); // 返回处理结果

        } catch (Exception e) { // 开始新的代码块
            String msg = e.getMessage() == null ? "" : e.getMessage(); // 执行赋值操作
            if (msg.contains("502") || msg.contains("Bad Gateway")) { // 进行条件判断
                log.error("LLM 上游网关异常（502 Bad Gateway），model={}, err={}", request.getModelName(), msg); // 执行赋值操作
            } else { // 切换到分支逻辑
                log.error("调用模型异常，model={}: {}", request.getModelName(), msg, e); // 执行赋值操作
            } // 结束当前代码块
            return null; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private boolean isInputSafe(String userInput) { // 定义方法签名
        try { // 尝试执行核心逻辑
            GeminiProperty.ModelConfig smallConfig = geminiProperty.getSmall(); // 执行赋值操作
            if (smallConfig == null) { // 进行条件判断
                log.warn("未配置 small model，跳过 Prompt Injection 检测"); // 执行语句逻辑
                return true; // 返回处理结果
            } // 结束当前代码块

            String detectionPrompt = String.format(PromptTemplates.PROMPT_INJECTION_DETECTION, userInput); // 执行赋值操作
            List<ChatMessage> safetyMessages = List.of(UserMessage.from(detectionPrompt)); // 执行赋值操作
            String result = executeChatCall(safetyMessages, smallConfig, 0.0); // 执行赋值操作

            boolean safe = result == null || !result.trim().toUpperCase().contains("UNSAFE"); // 执行赋值操作
            log.debug("Prompt Injection 检测完成，safe={}, result={}", safe, result); // 执行赋值操作
            return safe; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.warn("安全检测调用失败，默认放行: {}", e.getMessage()); // 执行语句逻辑
            return true; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String executeChatCall(List<ChatMessage> messages, GeminiProperty.ModelConfig config, Double temperature) { // 定义方法签名
        String baseUrl = config.getUrl(); // 执行赋值操作
        if (baseUrl != null) { // 进行条件判断
            baseUrl = baseUrl.replace("/chat/completions", "") // 执行赋值操作
                    .replace("/embeddings", ""); // 执行语句逻辑
        } // 结束当前代码块

        ChatLanguageModel chatModel = OpenAiChatModel.builder() // 执行赋值操作
                .baseUrl(baseUrl) // 执行当前逻辑
                .apiKey(config.getApiKey()) // 执行当前逻辑
                .modelName(config.getModelName()) // 执行当前逻辑
                .temperature(temperature != null ? temperature : 0.7) // 执行赋值操作
                .timeout(Duration.ofSeconds(120)) // 执行当前逻辑
                .maxRetries(3) // 执行当前逻辑
                .logRequests(true) // 执行当前逻辑
                .logResponses(true) // 执行当前逻辑
                .build(); // 执行语句逻辑

        Response<AiMessage> response = chatModel.generate(messages); // 执行赋值操作
        return response.content().text(); // 返回处理结果
    } // 结束当前代码块

    private String extractLatestUserTextForSafetyCheck(List<LlmMessage> messages) { // 定义方法签名
        if (messages == null || messages.isEmpty()) return null; // 进行条件判断
        for (int i = messages.size() - 1; i >= 0; i--) { // 执行循环处理
            LlmMessage msg = messages.get(i); // 执行赋值操作
            if ("system".equalsIgnoreCase(msg.getRole()) || "assistant".equalsIgnoreCase(msg.getRole())) { // 进行条件判断
                continue; // 执行语句逻辑
            } // 结束当前代码块
            String text = msg.getText(); // 执行赋值操作
            if (text == null || text.isBlank()) { // 进行条件判断
                continue; // 执行语句逻辑
            } // 结束当前代码块
            if (isLikelyInternalPrompt(text)) { // 进行条件判断
                log.debug("检测到疑似内部提示词，跳过安全检测输入提取"); // 执行语句逻辑
                return null; // 返回处理结果
            } // 结束当前代码块
            return text; // 返回处理结果
        } // 结束当前代码块
        return null; // 返回处理结果
    } // 结束当前代码块

    private boolean isLikelyInternalPrompt(String text) { // 定义方法签名
        return text.contains("# LUNA 核心人格宪章") // 返回处理结果
                || text.contains("# 输出修复指令") // 执行当前逻辑
                || text.contains("# 系统唤醒指令") // 执行当前逻辑
                || text.contains("# 运行时上下文层") // 执行当前逻辑
                || text.contains("# 记忆上下文注入层") // 执行当前逻辑
                || text.contains("仅输出修复后的单行合法JSON") // 执行当前逻辑
                || text.contains("你是一个安全检测系统"); // 执行语句逻辑
    } // 结束当前代码块

    private GeminiProperty.ModelConfig getModelConfig(String modelName) { // 定义方法签名
        if (modelName == null) return geminiProperty.getBig(); // 进行条件判断

        if (geminiProperty.getSmall() != null && modelName.equals(geminiProperty.getSmall().getModelName())) { // 进行条件判断
            return geminiProperty.getSmall(); // 返回处理结果
        } // 结束当前代码块
        if (geminiProperty.getMid() != null && modelName.equals(geminiProperty.getMid().getModelName())) { // 进行条件判断
            return geminiProperty.getMid(); // 返回处理结果
        } // 结束当前代码块
        if (geminiProperty.getBig() != null && modelName.equals(geminiProperty.getBig().getModelName())) { // 进行条件判断
            return geminiProperty.getBig(); // 返回处理结果
        } // 结束当前代码块
        if (geminiProperty.getFlash() != null && modelName.equals(geminiProperty.getFlash().getModelName())) { // 进行条件判断
            return geminiProperty.getFlash(); // 返回处理结果
        } // 结束当前代码块

        log.warn("请求模型 [{}] 未精确匹配，回退使用 big 配置", modelName); // 执行语句逻辑
        return geminiProperty.getBig(); // 返回处理结果
    } // 结束当前代码块

    public String getEmbedding(String text) throws Exception { // 定义方法签名
        if (text == null || text.isBlank()) { // 进行条件判断
            log.warn("getEmbedding 跳过：输入为空"); // 执行语句逻辑
            return null; // 返回处理结果
        } // 结束当前代码块

        String cached = embeddingCache.get(text); // 执行赋值操作
        if (cached != null && !cached.isBlank()) { // 进行条件判断
            log.debug("Embedding cache hit, textLength={}", text.length()); // 执行赋值操作
            return cached; // 返回处理结果
        } // 结束当前代码块

        if (inferenceHttpEnabled) { // 进行条件判断
            try { // 尝试执行核心逻辑
                String vector = callEmbeddingHttpService(text); // 执行赋值操作
                if (vector != null && !vector.isBlank()) { // 进行条件判断
                    cacheEmbedding(text, vector); // 执行语句逻辑
                    log.debug("Embedding via HTTP success, textLength={}", text.length()); // 执行赋值操作
                    return vector; // 返回处理结果
                } // 结束当前代码块
                log.warn("Embedding HTTP 返回为空，将尝试本地回退"); // 执行语句逻辑
            } catch (Exception e) { // 开始新的代码块
                log.warn("Embedding HTTP 调用失败: {}，fallbackLocal={}", e.getMessage(), fallbackLocal); // 执行赋值操作
                if (!fallbackLocal) { // 进行条件判断
                    throw e; // 抛出异常信息
                } // 结束当前代码块
            } // 结束当前代码块
        } // 结束当前代码块

        String vector = getEmbeddingByLocalProcess(text); // 执行赋值操作
        cacheEmbedding(text, vector); // 执行语句逻辑
        log.debug("Embedding via local process success, textLength={}", text.length()); // 执行赋值操作
        return vector; // 返回处理结果
    } // 结束当前代码块

    public List<Double> rerank(String query, List<String> documents) throws Exception { // 定义方法签名
        if (documents == null || documents.isEmpty()) { // 进行条件判断
            log.debug("rerank 跳过：documents 为空"); // 执行语句逻辑
            return new ArrayList<>(); // 返回处理结果
        } // 结束当前代码块

        if (inferenceHttpEnabled) { // 进行条件判断
            try { // 尝试执行核心逻辑
                List<Double> scores = callRerankHttpService(query, documents); // 执行赋值操作
                log.debug("rerank via HTTP success, docSize={}", documents.size()); // 执行赋值操作
                return scores; // 返回处理结果
            } catch (Exception e) { // 开始新的代码块
                log.warn("rerank HTTP 调用失败: {}，fallbackLocal={}", e.getMessage(), fallbackLocal); // 执行赋值操作
                if (!fallbackLocal) { // 进行条件判断
                    throw e; // 抛出异常信息
                } // 结束当前代码块
            } // 结束当前代码块
        } // 结束当前代码块

        List<Double> scores = rerankByLocalProcess(query, documents); // 执行赋值操作
        log.debug("rerank via local process success, docSize={}", documents.size()); // 执行赋值操作
        return scores; // 返回处理结果
    } // 结束当前代码块

    private String callEmbeddingHttpService(String text) throws Exception { // 定义方法签名
        Map<String, Object> body = new HashMap<>(); // 执行赋值操作
        body.put("text", text); // 执行语句逻辑
        String json = objectMapper.writeValueAsString(body); // 执行赋值操作

        Request request = new Request.Builder() // 执行赋值操作
                .url(embeddingServiceUrl) // 执行当前逻辑
                .post(RequestBody.create(json, MediaType.parse("application/json"))) // 执行当前逻辑
                .build(); // 执行语句逻辑

        try (okhttp3.Response response = httpClient.newBuilder() // 尝试执行核心逻辑
                .readTimeout(Duration.ofMillis(inferenceHttpTimeoutMs)) // 执行当前逻辑
                .build() // 执行当前逻辑
                .newCall(request) // 执行当前逻辑
                .execute()) { // 开始新的代码块
            if (!response.isSuccessful() || response.body() == null) { // 进行条件判断
                throw new IOException("HTTP embedding 响应异常: " + response.code()); // 抛出异常信息
            } // 结束当前代码块
            String respText = response.body().string(); // 执行赋值操作
            JsonNode node = objectMapper.readTree(respText); // 执行赋值操作
            if (!node.path("success").asBoolean(false)) { // 进行条件判断
                throw new RuntimeException("HTTP embedding 服务返回失败: " + node.path("error_message").asText("")); // 抛出异常信息
            } // 结束当前代码块
            return node.path("vector_json").asText(); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private List<Double> callRerankHttpService(String query, List<String> documents) throws Exception { // 定义方法签名
        Map<String, Object> body = new HashMap<>(); // 执行赋值操作
        body.put("query", query); // 执行语句逻辑
        body.put("documents", documents); // 执行语句逻辑
        String json = objectMapper.writeValueAsString(body); // 执行赋值操作

        Request request = new Request.Builder() // 执行赋值操作
                .url(rerankServiceUrl) // 执行当前逻辑
                .post(RequestBody.create(json, MediaType.parse("application/json"))) // 执行当前逻辑
                .build(); // 执行语句逻辑

        try (okhttp3.Response response = httpClient.newBuilder() // 尝试执行核心逻辑
                .readTimeout(Duration.ofMillis(inferenceHttpTimeoutMs)) // 执行当前逻辑
                .build() // 执行当前逻辑
                .newCall(request) // 执行当前逻辑
                .execute()) { // 开始新的代码块
            if (!response.isSuccessful() || response.body() == null) { // 进行条件判断
                throw new IOException("HTTP rerank 响应异常: " + response.code()); // 抛出异常信息
            } // 结束当前代码块
            String respText = response.body().string(); // 执行赋值操作
            JsonNode node = objectMapper.readTree(respText); // 执行赋值操作
            if (!node.path("success").asBoolean(false)) { // 进行条件判断
                throw new RuntimeException("HTTP rerank 服务返回失败: " + node.path("error_message").asText("")); // 抛出异常信息
            } // 结束当前代码块
            List<Double> scores = new ArrayList<>(); // 执行赋值操作
            JsonNode scoreNode = node.path("scores"); // 执行赋值操作
            if (scoreNode.isArray()) { // 进行条件判断
                for (JsonNode n : scoreNode) { // 执行循环处理
                    scores.add(n.asDouble()); // 执行语句逻辑
                } // 结束当前代码块
            } // 结束当前代码块
            return scores; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String getEmbeddingByLocalProcess(String text) throws Exception { // 定义方法签名
        String pythonPath = embeddingProperty.getPythonPath(); // 执行赋值操作
        String scriptPath = resolveScriptPath(embeddingProperty.getScriptPath(), "embedding.py"); // 执行赋值操作
        String modelPath = embeddingProperty.getModelPath(); // 执行赋值操作

        log.info("执行本地 Embedding 脚本，pythonPath={}, scriptPath={}", pythonPath, scriptPath); // 执行赋值操作

        ProcessBuilder pb = new ProcessBuilder( // 执行赋值操作
                pythonPath, // 执行当前逻辑
                scriptPath, // 执行当前逻辑
                modelPath, // 执行当前逻辑
                text // 执行当前逻辑
        ); // 执行语句逻辑

        Process process = pb.start(); // 执行赋值操作

        StringBuilder output = new StringBuilder(); // 执行赋值操作
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) { // 尝试执行核心逻辑
            String line; // 执行语句逻辑
            while ((line = reader.readLine()) != null) { // 执行循环判断
                output.append(line); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        StringBuilder errorOutput = new StringBuilder(); // 执行赋值操作
        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) { // 尝试执行核心逻辑
            String line; // 执行语句逻辑
            while ((line = errorReader.readLine()) != null) { // 执行循环判断
                errorOutput.append(line).append("\n"); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        int exitCode = process.waitFor(); // 执行赋值操作

        if (exitCode != 0) { // 进行条件判断
            String errorMsg = errorOutput.toString(); // 执行赋值操作
            log.error("Python Embedding 脚本执行失败 (exitCode={})，stderr={}", exitCode, errorMsg); // 执行赋值操作
            throw new RuntimeException("Python脚本执行异常: " + errorMsg); // 抛出异常信息
        } // 结束当前代码块

        String result = output.toString().trim(); // 执行赋值操作
        if (result.isEmpty()) { // 进行条件判断
            String errorMsg = errorOutput.toString(); // 执行赋值操作
            log.error("Python Embedding 脚本返回为空，stderr={}", errorMsg); // 执行赋值操作
            throw new RuntimeException("Python脚本返回为空. Stderr: " + errorMsg); // 抛出异常信息
        } // 结束当前代码块

        return result; // 返回处理结果
    } // 结束当前代码块

    private List<Double> rerankByLocalProcess(String query, List<String> documents) throws Exception { // 定义方法签名
        if (rerankModelPath == null || rerankModelPath.isEmpty()) { // 进行条件判断
            throw new IllegalStateException("Rerank 模型路径未配置 (rerank.model-path)"); // 抛出异常信息
        } // 结束当前代码块

        String pythonPath = embeddingProperty.getPythonPath(); // 执行赋值操作
        String scriptPath = resolveScriptPath(rerankScriptPath, "rerank.py"); // 执行赋值操作

        log.info("执行本地 Rerank 脚本，pythonPath={}, scriptPath={}, docSize={}", pythonPath, scriptPath, documents.size()); // 执行赋值操作

        ProcessBuilder pb = new ProcessBuilder( // 执行赋值操作
                pythonPath, // 执行当前逻辑
                scriptPath, // 执行当前逻辑
                rerankModelPath // 执行当前逻辑
        ); // 执行语句逻辑

        Process process = pb.start(); // 执行赋值操作

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) { // 尝试执行核心逻辑
            Map<String, Object> inputPayload = new HashMap<>(); // 执行赋值操作
            inputPayload.put("query", query); // 执行语句逻辑
            inputPayload.put("documents", documents); // 执行语句逻辑

            String jsonInput = objectMapper.writeValueAsString(inputPayload); // 执行赋值操作
            writer.write(jsonInput); // 执行语句逻辑
            writer.flush(); // 执行语句逻辑
        } // 结束当前代码块

        StringBuilder output = new StringBuilder(); // 执行赋值操作
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) { // 尝试执行核心逻辑
            String line; // 执行语句逻辑
            while ((line = reader.readLine()) != null) { // 执行循环判断
                output.append(line); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        StringBuilder errorOutput = new StringBuilder(); // 执行赋值操作
        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) { // 尝试执行核心逻辑
            String line; // 执行语句逻辑
            while ((line = errorReader.readLine()) != null) { // 执行循环判断
                errorOutput.append(line).append("\n"); // 执行语句逻辑
            } // 结束当前代码块
        } // 结束当前代码块

        int exitCode = process.waitFor(); // 执行赋值操作

        if (exitCode != 0) { // 进行条件判断
            String errorMsg = errorOutput.toString(); // 执行赋值操作
            log.error("Python Rerank 脚本执行失败 (exitCode={})，stderr={}", exitCode, errorMsg); // 执行赋值操作
            throw new RuntimeException("Python Rerank 脚本执行异常: " + errorMsg); // 抛出异常信息
        } // 结束当前代码块

        String result = output.toString().trim(); // 执行赋值操作
        if (result.isEmpty()) { // 进行条件判断
            String errorMsg = errorOutput.toString(); // 执行赋值操作
            log.error("Python Rerank 脚本返回为空，stderr={}", errorMsg); // 执行赋值操作
            throw new RuntimeException("Python Rerank 脚本返回为空. Stderr: " + errorMsg); // 抛出异常信息
        } // 结束当前代码块

        return objectMapper.readValue(result, objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)); // 返回处理结果
    } // 结束当前代码块

    private void cacheEmbedding(String text, String vector) { // 定义方法签名
        if (vector == null || vector.isBlank()) { // 进行条件判断
            return; // 返回处理结果
        } // 结束当前代码块
        if (embeddingCache.size() > 2000) { // 进行条件判断
            log.info("Embedding cache size={} 超过阈值，执行清空", embeddingCache.size()); // 执行赋值操作
            embeddingCache.clear(); // 执行语句逻辑
        } // 结束当前代码块
        embeddingCache.put(text, vector); // 执行语句逻辑
    } // 结束当前代码块

    public <T> List<T> rerankResources(List<T> resources, List<Double> scores, int topK) { // 定义方法签名
        if (resources == null || resources.isEmpty()) return Collections.emptyList(); // 进行条件判断
        if (scores == null || scores.isEmpty()) { // 进行条件判断
            log.debug("rerankResources：scores 为空，直接按原顺序截断 topK={}", topK); // 执行赋值操作
            return resources.stream().limit(topK).toList(); // 返回处理结果
        } // 结束当前代码块

        int n = Math.min(resources.size(), scores.size()); // 执行赋值操作
        return IntStream.range(0, n) // 返回处理结果
                .boxed() // 执行当前逻辑
                .sorted((i, j) -> Double.compare(scores.get(j), scores.get(i))) // 执行当前逻辑
                .limit(topK) // 执行当前逻辑
                .map(resources::get) // 执行当前逻辑
                .toList(); // 执行语句逻辑
    } // 结束当前代码块

    private String resolveScriptPath(String configuredPath, String resourceName) throws IOException { // 定义方法签名
        if (scriptPathCache.containsKey(resourceName)) { // 进行条件判断
            String cachedPath = scriptPathCache.get(resourceName); // 执行赋值操作
            if (new File(cachedPath).exists()) { // 进行条件判断
                return cachedPath; // 返回处理结果
            } // 结束当前代码块
            log.warn("脚本缓存路径已失效，将重新解析，resourceName={}, cachedPath={}", resourceName, cachedPath); // 执行赋值操作
        } // 结束当前代码块

        if (configuredPath != null && !configuredPath.isEmpty()) { // 进行条件判断
            File file = new File(configuredPath); // 执行赋值操作
            if (file.exists()) { // 进行条件判断
                scriptPathCache.put(resourceName, configuredPath); // 执行语句逻辑
                return configuredPath; // 返回处理结果
            } // 结束当前代码块
        } // 结束当前代码块

        File devResourceFile = new File("src/main/resources/python/" + resourceName); // 执行赋值操作
        if (devResourceFile.exists()) { // 进行条件判断
            String devPath = devResourceFile.getPath(); // 执行赋值操作
            scriptPathCache.put(resourceName, devPath); // 执行语句逻辑
            log.info("脚本路径自动探测成功（源码资源路径），resourceName={}, path={}", resourceName, devPath); // 执行赋值操作
            return devPath; // 返回处理结果
        } // 结束当前代码块

        log.warn("配置脚本路径不存在: {}，且源码资源路径未找到，尝试从 classpath 加载", configuredPath); // 执行语句逻辑
        String resourcePath = "python/" + resourceName; // 执行赋值操作
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) { // 尝试执行核心逻辑
            if (is == null) { // 进行条件判断
                throw new FileNotFoundException("无法在磁盘或 Classpath 中找到 " + resourceName + "。配置路径: " + configuredPath); // 抛出异常信息
            } // 结束当前代码块

            String prefix = "luna_" + resourceName.replace(".py", "") + "_"; // 执行赋值操作
            File tempFile = File.createTempFile(prefix, ".py"); // 执行赋值操作
            tempFile.deleteOnExit(); // 执行语句逻辑

            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING); // 执行语句逻辑

            log.info("已从 Classpath 提取脚本 [{}] 到临时文件: {}", resourceName, tempFile.getAbsolutePath()); // 执行语句逻辑
            String tempPath = tempFile.getAbsolutePath(); // 执行赋值操作
            scriptPathCache.put(resourceName, tempPath); // 执行语句逻辑
            return tempPath; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
