package org.yilena.luna.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
public class LlmClientUtil {

    private final GeminiProperty geminiProperty;
    private final EmbeddingProperty embeddingProperty;
    private final ObjectMapper objectMapper;

    @Value("${rerank.model-path:}")
    private String rerankModelPath;

    @Value("${rerank.script-path:./python/rerank.py}")
    private String rerankScriptPath;

    private static final Map<String, String> scriptPathCache = new ConcurrentHashMap<>();
    private static final Map<String, String> embeddingCache = new ConcurrentHashMap<>();

    public LlmResponse generate(LlmRequest request) {
        if (request.getModelType() == ModelType.OPENAI_COMPATIBLE) {
            return callOpenAiCompatible(request);
        }
        throw new UnsupportedOperationException("暂不支持的模型类型: " + request.getModelType());
    }

    private LlmResponse callOpenAiCompatible(LlmRequest request) {
        try {
            String userLatestText = extractLatestUserTextForSafetyCheck(request.getMessages());

            boolean enablePromptInjectionCheck = request.getEnablePromptInjectionCheck() == null || request.getEnablePromptInjectionCheck();
            if (enablePromptInjectionCheck && userLatestText != null && !userLatestText.isEmpty()) {
                boolean isSafe = isInputSafe(userLatestText);
                if (!isSafe) {
                    log.warn("检测到潜在的 Prompt Injection 攻击，已拦截。User Input: {}", userLatestText);
                    return LlmResponse.builder()
                            .content("由于触发了安全过滤，我无法完成此请求。")
                            .build();
                }
            }

            List<ChatMessage> messages = new ArrayList<>();
            for (LlmMessage msg : request.getMessages()) {
                if ("system".equalsIgnoreCase(msg.getRole())) {
                    String hardenedSystemPrompt = msg.getText() + PromptTemplates.SYSTEM_SECURITY_NOTICE;
                    messages.add(SystemMessage.from(hardenedSystemPrompt));
                } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                    messages.add(AiMessage.from(msg.getText()));
                } else {
                    String safeText = msg.getText() != null ? msg.getText() : "";
                    String wrappedText = "<user_input>\n" + safeText + "\n</user_input>";

                    if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                        List<Content> contents = new ArrayList<>();
                        if (!safeText.isEmpty()) {
                            contents.add(TextContent.from(wrappedText));
                        }
                        for (String imageUrl : msg.getImageUrls()) {
                            contents.add(ImageContent.from(imageUrl));
                        }
                        messages.add(UserMessage.from(contents));
                    } else {
                        messages.add(UserMessage.from(wrappedText));
                    }
                }
            }

            String requestModelName = request.getModelName();
            GeminiProperty.ModelConfig config = getModelConfig(requestModelName);

            if (config == null) {
                log.error("未找到模型名称 [{}] 对应的配置信息，请检查 application.yaml", requestModelName);
                return null;
            }

            String responseText = executeChatCall(messages, config, request.getTemperature());
            return LlmResponse.builder().content(responseText).build();

        } catch (Exception e) {
            log.error("调用模型异常，model={}: {}", request.getModelName(), e.getMessage(), e);
            return null;
        }
    }

    private boolean isInputSafe(String userInput) {
        try {
            GeminiProperty.ModelConfig smallConfig = geminiProperty.getSmall();
            if (smallConfig == null) {
                log.warn("未配置 Small Model，跳过 Prompt Injection 检测");
                return true;
            }

            String detectionPrompt = String.format(PromptTemplates.PROMPT_INJECTION_DETECTION, userInput);
            List<ChatMessage> safetyMessages = List.of(UserMessage.from(detectionPrompt));
            String result = executeChatCall(safetyMessages, smallConfig, 0.0);

            return result == null || !result.trim().toUpperCase().contains("UNSAFE");
        } catch (Exception e) {
            log.warn("安全检测调用失败，默认放行: {}", e.getMessage());
            return true;
        }
    }

    private String executeChatCall(List<ChatMessage> messages, GeminiProperty.ModelConfig config, Double temperature) {
        String baseUrl = config.getUrl();
        if (baseUrl != null) {
            baseUrl = baseUrl.replace("/chat/completions", "")
                    .replace("/embeddings", "");
        }

        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(temperature != null ? temperature : 0.7)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(3)
                .logRequests(true)
                .logResponses(true)
                .build();

        Response<AiMessage> response = chatModel.generate(messages);
        return response.content().text();
    }

    private String extractLatestUserTextForSafetyCheck(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage msg = messages.get(i);
            if ("system".equalsIgnoreCase(msg.getRole()) || "assistant".equalsIgnoreCase(msg.getRole())) {
                continue;
            }
            String text = msg.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (isLikelyInternalPrompt(text)) {
                log.debug("检测到内部提示词消息，跳过安全检测。");
                return null;
            }
            return text;
        }
        return null;
    }

    private boolean isLikelyInternalPrompt(String text) {
        return text.contains("# LUNA 核心人格宪章")
                || text.contains("# 输出修复指令")
                || text.contains("# 系统唤醒指令")
                || text.contains("# 运行时上下文层")
                || text.contains("# 记忆上下文注入层")
                || text.contains("仅输出修复后的单行合法JSON")
                || text.contains("你是一个安全检测系统");
    }

    private GeminiProperty.ModelConfig getModelConfig(String modelName) {
        if (modelName == null) return geminiProperty.getBig();

        if (geminiProperty.getSmall() != null && modelName.equals(geminiProperty.getSmall().getModelName())) {
            return geminiProperty.getSmall();
        }
        if (geminiProperty.getMid() != null && modelName.equals(geminiProperty.getMid().getModelName())) {
            return geminiProperty.getMid();
        }
        if (geminiProperty.getBig() != null && modelName.equals(geminiProperty.getBig().getModelName())) {
            return geminiProperty.getBig();
        }
        if (geminiProperty.getFlash() != null && modelName.equals(geminiProperty.getFlash().getModelName())) {
            return geminiProperty.getFlash();
        }

        log.warn("请求的模型 [{}] 未在配置中找到精确匹配，将默认使用 Big Model 的 URL 和 Key", modelName);
        return geminiProperty.getBig();
    }

    public String getEmbedding(String text) throws Exception {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cached = embeddingCache.get(text);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        String pythonPath = embeddingProperty.getPythonPath();
        String scriptPath = resolveScriptPath(embeddingProperty.getScriptPath(), "embedding.py");
        String modelPath = embeddingProperty.getModelPath();

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                modelPath,
                text
        );

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorMsg = errorOutput.toString();
            log.error("Python Embedding 脚本执行失败 (ExitCode: {}). Stderr: {}", exitCode, errorMsg);
            throw new RuntimeException("Python脚本执行异常: " + errorMsg);
        }

        String result = output.toString().trim();
        if (result.isEmpty()) {
            String errorMsg = errorOutput.toString();
            log.error("Python Embedding 脚本返回为空. Stderr: {}", errorMsg);
            throw new RuntimeException("Python脚本返回为空. Stderr: " + errorMsg);
        }

        if (embeddingCache.size() > 2000) {
            embeddingCache.clear();
        }
        embeddingCache.put(text, result);
        return result;
    }

    public List<Double> rerank(String query, List<String> documents) throws Exception {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        if (rerankModelPath == null || rerankModelPath.isEmpty()) {
            throw new IllegalStateException("Rerank 模型路径未配置 (rerank.model-path)");
        }

        String pythonPath = embeddingProperty.getPythonPath();
        String scriptPath = resolveScriptPath(rerankScriptPath, "rerank.py");

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                rerankModelPath
        );

        Process process = pb.start();

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Map<String, Object> inputPayload = new HashMap<>();
            inputPayload.put("query", query);
            inputPayload.put("documents", documents);

            String jsonInput = objectMapper.writeValueAsString(inputPayload);
            writer.write(jsonInput);
            writer.flush();
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String errorMsg = errorOutput.toString();
            log.error("Python Rerank 脚本执行失败 (ExitCode: {}). Stderr: {}", exitCode, errorMsg);
            throw new RuntimeException("Python Rerank 脚本执行异常: " + errorMsg);
        }

        String result = output.toString().trim();
        if (result.isEmpty()) {
            String errorMsg = errorOutput.toString();
            log.error("Python Rerank 脚本返回为空. Stderr: {}", errorMsg);
            throw new RuntimeException("Python Rerank 脚本返回为空. Stderr: " + errorMsg);
        }

        return objectMapper.readValue(result, objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
    }

    public <T> List<T> rerankResources(List<T> resources, List<Double> scores, int topK) {
        if (resources == null || resources.isEmpty()) return Collections.emptyList();
        if (scores == null || scores.isEmpty()) {
            return resources.stream().limit(topK).toList();
        }

        int n = Math.min(resources.size(), scores.size());
        return IntStream.range(0, n)
                .boxed()
                .sorted((i, j) -> Double.compare(scores.get(j), scores.get(i)))
                .limit(topK)
                .map(resources::get)
                .toList();
    }

    private String resolveScriptPath(String configuredPath, String resourceName) throws IOException {
        if (scriptPathCache.containsKey(resourceName)) {
            String cachedPath = scriptPathCache.get(resourceName);
            if (new File(cachedPath).exists()) {
                return cachedPath;
            }
        }

        if (configuredPath != null && !configuredPath.isEmpty()) {
            File file = new File(configuredPath);
            if (file.exists()) {
                scriptPathCache.put(resourceName, configuredPath);
                return configuredPath;
            }
            log.warn("配置的脚本路径不存在: {}，将尝试从 Classpath 加载", configuredPath);
        }

        String resourcePath = "python/" + resourceName;
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new FileNotFoundException("无法在磁盘或 Classpath 中找到 " + resourceName + "。配置路径: " + configuredPath);
            }

            String prefix = "luna_" + resourceName.replace(".py", "") + "_";
            File tempFile = File.createTempFile(prefix, ".py");
            tempFile.deleteOnExit();

            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            log.info("已从 Classpath 提取脚本 [{}] 到临时文件: {}", resourceName, tempFile.getAbsolutePath());
            String tempPath = tempFile.getAbsolutePath();
            scriptPathCache.put(resourceName, tempPath);
            return tempPath;
        }
    }
}
