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
public class LlmClientUtil {

    /**
     * 多模型配置（small/mid/big/flash）来源于 application.yaml -> gemini
     */
    private final GeminiProperty geminiProperty;

    /**
     * Embedding 本地脚本与模型配置（python 路径、script 路径、model 路径）
     */
    private final EmbeddingProperty embeddingProperty;

    /**
     * 通用 JSON 读写工具
     */
    private final ObjectMapper objectMapper;

    /**
     * rerank 模型路径（本地进程回退方案用）
     */
    @Value("${rerank.model-path:}")
    private String rerankModelPath;

    /**
     * rerank 脚本路径（本地进程回退方案用）
     */
    @Value("${rerank.script-path:./python/rerank.py}")
    private String rerankScriptPath;

    /**
     * 是否启用常驻 HTTP 推理服务（方案A）
     */
    @Value("${inference.http.enabled:true}")
    private boolean inferenceHttpEnabled;

    /**
     * embedding HTTP 服务地址
     */
    @Value("${inference.http.embedding-url:http://127.0.0.1:18080/embedding}")
    private String embeddingServiceUrl;

    /**
     * rerank HTTP 服务地址
     */
    @Value("${inference.http.rerank-url:http://127.0.0.1:18081/rerank}")
    private String rerankServiceUrl;

    /**
     * HTTP 推理超时时间（毫秒）
     */
    @Value("${inference.http.timeout-ms:1500}")
    private long inferenceHttpTimeoutMs;

    /**
     * HTTP 推理失败后是否允许本地脚本回退
     */
    @Value("${inference.http.fallback-local:true}")
    private boolean fallbackLocal;

    /**
     * 脚本路径缓存：resourceName -> resolvedPath
     * 目的：避免每次都从 classpath 提取临时文件
     */
    private static final Map<String, String> scriptPathCache = new ConcurrentHashMap<>();

    /**
     * embedding 结果缓存：text -> vector_json
     * 目的：减少重复向量化请求
     */
    private static final Map<String, String> embeddingCache = new ConcurrentHashMap<>();

    /**
     * 通用 HTTP 客户端
     * 注意：具体请求时会通过 newBuilder 覆盖 readTimeout（按配置动态值）
     */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofMillis(1500))
            .readTimeout(Duration.ofMillis(1500))
            .writeTimeout(Duration.ofMillis(1500))
            .build();

    /**
     * 对外统一入口：根据模型类型分发调用
     */
    public LlmResponse generate(LlmRequest request) {
        if (request == null) {
            log.error("generate 调用失败：request 为空");
            return null;
        }
        log.info("开始调用 LLM，modelType={}, modelName={}", request.getModelType(), request.getModelName());

        if (request.getModelType() == ModelType.OPENAI_COMPATIBLE) {
            return callOpenAiCompatible(request);
        }

        log.error("暂不支持的模型类型: {}", request.getModelType());
        throw new UnsupportedOperationException("暂不支持的模型类型: " + request.getModelType());
    }

    /**
     * OpenAI 兼容协议调用逻辑（当前核心链路）
     */
    private LlmResponse callOpenAiCompatible(LlmRequest request) {
        try {
            // 1) 提取最近一条用户文本，作为 Prompt Injection 检测输入
            String userLatestText = extractLatestUserTextForSafetyCheck(request.getMessages());

            // 2) 默认启用检测，除非调用方显式关闭
            boolean enablePromptInjectionCheck = request.getEnablePromptInjectionCheck() == null || request.getEnablePromptInjectionCheck();

            // 3) 安全检测（仅对有效用户输入执行）
            if (enablePromptInjectionCheck && userLatestText != null && !userLatestText.isEmpty()) {
                boolean isSafe = isInputSafe(userLatestText);
                if (!isSafe) {
                    log.warn("检测到潜在 Prompt Injection，已拦截，输入长度={}", userLatestText.length());
                    return LlmResponse.builder()
                            .content("由于触发了安全过滤，我无法完成此请求。")
                            .build();
                }
            } else {
                log.debug("本次跳过 Prompt Injection 检测，enableCheck={}, hasUserText={}",
                        enablePromptInjectionCheck, userLatestText != null && !userLatestText.isEmpty());
            }

            // 4) 将统一 LlmMessage 映射为 LangChain4j ChatMessage
            List<ChatMessage> messages = new ArrayList<>();
            if (request.getMessages() != null) {
                for (LlmMessage msg : request.getMessages()) {
                    if ("system".equalsIgnoreCase(msg.getRole())) {
                        // system 消息追加安全提示，强化边界
                        String hardenedSystemPrompt = msg.getText() + PromptTemplates.SYSTEM_SECURITY_NOTICE;
                        messages.add(SystemMessage.from(hardenedSystemPrompt));
                    } else if ("assistant".equalsIgnoreCase(msg.getRole())) {
                        messages.add(AiMessage.from(msg.getText()));
                    } else {
                        // user / 默认角色：统一用 <user_input> 包裹，降低指令污染风险
                        String safeText = msg.getText() != null ? msg.getText() : "";
                        String wrappedText = "<user_input>\n" + safeText + "\n</user_input>";

                        // 支持多模态：文本 + 图片 URL
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
            }

            // 5) 根据 modelName 解析对应配置（URL/API Key/实际 model）
            String requestModelName = request.getModelName();
            GeminiProperty.ModelConfig config = getModelConfig(requestModelName);

            if (config == null) {
                log.error("未找到模型 [{}] 对应配置，请检查 application.yaml", requestModelName);
                return null;
            }

            // 6) 发起对话调用并返回
            String responseText = executeChatCall(messages, config, request.getTemperature());
            log.info("LLM 调用完成，model={}, responseLength={}",
                    config.getModelName(), responseText != null ? responseText.length() : 0);
            return LlmResponse.builder().content(responseText).build();

        } catch (Exception e) {
            log.error("调用模型异常，model={}: {}", request.getModelName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Prompt Injection 检测（小模型低温）
     * 返回 true = 安全，false = 不安全
     */
    private boolean isInputSafe(String userInput) {
        try {
            GeminiProperty.ModelConfig smallConfig = geminiProperty.getSmall();
            if (smallConfig == null) {
                log.warn("未配置 small model，跳过 Prompt Injection 检测");
                return true;
            }

            String detectionPrompt = String.format(PromptTemplates.PROMPT_INJECTION_DETECTION, userInput);
            List<ChatMessage> safetyMessages = List.of(UserMessage.from(detectionPrompt));
            String result = executeChatCall(safetyMessages, smallConfig, 0.0);

            boolean safe = result == null || !result.trim().toUpperCase().contains("UNSAFE");
            log.debug("Prompt Injection 检测完成，safe={}, result={}", safe, result);
            return safe;
        } catch (Exception e) {
            // 检测失败不阻断主流程，默认放行（可用性优先）
            log.warn("安全检测调用失败，默认放行: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 实际 Chat API 调用（LangChain4j OpenAI compatible）
     */
    private String executeChatCall(List<ChatMessage> messages, GeminiProperty.ModelConfig config, Double temperature) {
        // 1) 兼容处理 baseUrl：去掉尾部固定 path，传给 OpenAiChatModel
        String baseUrl = config.getUrl();
        if (baseUrl != null) {
            baseUrl = baseUrl.replace("/chat/completions", "")
                    .replace("/embeddings", "");
        }

        // 2) 构建模型客户端
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

        // 3) 调用并返回文本
        Response<AiMessage> response = chatModel.generate(messages);
        return response.content().text();
    }

    /**
     * 从消息列表末尾回溯，提取最后一条用户文本用于安全检测
     * 会过滤系统/助手消息，并跳过疑似内部提示词
     */
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
                log.debug("检测到疑似内部提示词，跳过安全检测输入提取");
                return null;
            }
            return text;
        }
        return null;
    }

    /**
     * 简单规则：识别内部 Prompt 片段，避免误当作用户输入做注入检测
     */
    private boolean isLikelyInternalPrompt(String text) {
        return text.contains("# LUNA 核心人格宪章")
                || text.contains("# 输出修复指令")
                || text.contains("# 系统唤醒指令")
                || text.contains("# 运行时上下文层")
                || text.contains("# 记忆上下文注入层")
                || text.contains("仅输出修复后的单行合法JSON")
                || text.contains("你是一个安全检测系统");
    }

    /**
     * 根据请求 modelName 定位配置；找不到时回退到 big
     */
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

        log.warn("请求模型 [{}] 未精确匹配，回退使用 big 配置", modelName);
        return geminiProperty.getBig();
    }

    /**
     * 获取 embedding（优先 HTTP 常驻服务，失败可回退本地进程）
     */
    public String getEmbedding(String text) throws Exception {
        // 1) 输入判空
        if (text == null || text.isBlank()) {
            log.warn("getEmbedding 跳过：输入为空");
            return null;
        }

        // 2) 命中缓存直接返回
        String cached = embeddingCache.get(text);
        if (cached != null && !cached.isBlank()) {
            log.debug("Embedding cache hit, textLength={}", text.length());
            return cached;
        }

        // 3) 优先 HTTP 服务
        if (inferenceHttpEnabled) {
            try {
                String vector = callEmbeddingHttpService(text);
                if (vector != null && !vector.isBlank()) {
                    cacheEmbedding(text, vector);
                    log.debug("Embedding via HTTP success, textLength={}", text.length());
                    return vector;
                }
                log.warn("Embedding HTTP 返回为空，将尝试本地回退");
            } catch (Exception e) {
                log.warn("Embedding HTTP 调用失败: {}，fallbackLocal={}", e.getMessage(), fallbackLocal);
                if (!fallbackLocal) {
                    throw e;
                }
            }
        }

        // 4) 本地脚本回退
        String vector = getEmbeddingByLocalProcess(text);
        cacheEmbedding(text, vector);
        log.debug("Embedding via local process success, textLength={}", text.length());
        return vector;
    }

    /**
     * rerank（优先 HTTP 常驻服务，失败可回退本地进程）
     */
    public List<Double> rerank(String query, List<String> documents) throws Exception {
        // 1) 输入检查
        if (documents == null || documents.isEmpty()) {
            log.debug("rerank 跳过：documents 为空");
            return new ArrayList<>();
        }

        // 2) 优先 HTTP 服务
        if (inferenceHttpEnabled) {
            try {
                List<Double> scores = callRerankHttpService(query, documents);
                log.debug("rerank via HTTP success, docSize={}", documents.size());
                return scores;
            } catch (Exception e) {
                log.warn("rerank HTTP 调用失败: {}，fallbackLocal={}", e.getMessage(), fallbackLocal);
                if (!fallbackLocal) {
                    throw e;
                }
            }
        }

        // 3) 本地脚本回退
        List<Double> scores = rerankByLocalProcess(query, documents);
        log.debug("rerank via local process success, docSize={}", documents.size());
        return scores;
    }

    /**
     * 调用 embedding HTTP 服务
     * 协议：POST /embedding -> { vector_json, success, error_message }
     */
    private String callEmbeddingHttpService(String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        String json = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(embeddingServiceUrl)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (okhttp3.Response response = httpClient.newBuilder()
                .readTimeout(Duration.ofMillis(inferenceHttpTimeoutMs))
                .build()
                .newCall(request)
                .execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP embedding 响应异常: " + response.code());
            }
            String respText = response.body().string();
            JsonNode node = objectMapper.readTree(respText);
            if (!node.path("success").asBoolean(false)) {
                throw new RuntimeException("HTTP embedding 服务返回失败: " + node.path("error_message").asText(""));
            }
            return node.path("vector_json").asText();
        }
    }

    /**
     * 调用 rerank HTTP 服务
     * 协议：POST /rerank -> { scores, success, error_message }
     */
    private List<Double> callRerankHttpService(String query, List<String> documents) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("documents", documents);
        String json = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(rerankServiceUrl)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (okhttp3.Response response = httpClient.newBuilder()
                .readTimeout(Duration.ofMillis(inferenceHttpTimeoutMs))
                .build()
                .newCall(request)
                .execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP rerank 响应异常: " + response.code());
            }
            String respText = response.body().string();
            JsonNode node = objectMapper.readTree(respText);
            if (!node.path("success").asBoolean(false)) {
                throw new RuntimeException("HTTP rerank 服务返回失败: " + node.path("error_message").asText(""));
            }
            List<Double> scores = new ArrayList<>();
            JsonNode scoreNode = node.path("scores");
            if (scoreNode.isArray()) {
                for (JsonNode n : scoreNode) {
                    scores.add(n.asDouble());
                }
            }
            return scores;
        }
    }

    /**
     * 本地 Python 进程执行 embedding 脚本
     */
    private String getEmbeddingByLocalProcess(String text) throws Exception {
        String pythonPath = embeddingProperty.getPythonPath();
        String scriptPath = resolveScriptPath(embeddingProperty.getScriptPath(), "embedding.py");
        String modelPath = embeddingProperty.getModelPath();

        log.info("执行本地 Embedding 脚本，pythonPath={}, scriptPath={}", pythonPath, scriptPath);

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                modelPath,
                text
        );

        Process process = pb.start();

        // 读取标准输出（向量字符串）
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        // 读取标准错误（异常信息）
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
            log.error("Python Embedding 脚本执行失败 (exitCode={})，stderr={}", exitCode, errorMsg);
            throw new RuntimeException("Python脚本执行异常: " + errorMsg);
        }

        String result = output.toString().trim();
        if (result.isEmpty()) {
            String errorMsg = errorOutput.toString();
            log.error("Python Embedding 脚本返回为空，stderr={}", errorMsg);
            throw new RuntimeException("Python脚本返回为空. Stderr: " + errorMsg);
        }

        return result;
    }

    /**
     * 本地 Python 进程执行 rerank 脚本（stdin 传 query/documents）
     */
    private List<Double> rerankByLocalProcess(String query, List<String> documents) throws Exception {
        if (rerankModelPath == null || rerankModelPath.isEmpty()) {
            throw new IllegalStateException("Rerank 模型路径未配置 (rerank.model-path)");
        }

        String pythonPath = embeddingProperty.getPythonPath();
        String scriptPath = resolveScriptPath(rerankScriptPath, "rerank.py");

        log.info("执行本地 Rerank 脚本，pythonPath={}, scriptPath={}, docSize={}", pythonPath, scriptPath, documents.size());

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                rerankModelPath
        );

        Process process = pb.start();

        // 通过 stdin 传入 JSON payload
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Map<String, Object> inputPayload = new HashMap<>();
            inputPayload.put("query", query);
            inputPayload.put("documents", documents);

            String jsonInput = objectMapper.writeValueAsString(inputPayload);
            writer.write(jsonInput);
            writer.flush();
        }

        // 读取 stdout（分数数组）
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        // 读取 stderr（异常信息）
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
            log.error("Python Rerank 脚本执行失败 (exitCode={})，stderr={}", exitCode, errorMsg);
            throw new RuntimeException("Python Rerank 脚本执行异常: " + errorMsg);
        }

        String result = output.toString().trim();
        if (result.isEmpty()) {
            String errorMsg = errorOutput.toString();
            log.error("Python Rerank 脚本返回为空，stderr={}", errorMsg);
            throw new RuntimeException("Python Rerank 脚本返回为空. Stderr: " + errorMsg);
        }

        return objectMapper.readValue(result, objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
    }

    /**
     * embedding 缓存写入（带简单容量保护）
     */
    private void cacheEmbedding(String text, String vector) {
        if (vector == null || vector.isBlank()) {
            return;
        }
        // 粗粒度控制：超过阈值直接清空，防止无界增长
        if (embeddingCache.size() > 2000) {
            log.info("Embedding cache size={} 超过阈值，执行清空", embeddingCache.size());
            embeddingCache.clear();
        }
        embeddingCache.put(text, vector);
    }

    /**
     * 根据 rerank 分数对资源重排并截断 topK
     */
    public <T> List<T> rerankResources(List<T> resources, List<Double> scores, int topK) {
        if (resources == null || resources.isEmpty()) return Collections.emptyList();
        if (scores == null || scores.isEmpty()) {
            log.debug("rerankResources：scores 为空，直接按原顺序截断 topK={}", topK);
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

    /**
     * 解析脚本路径：
     * 1) 优先缓存路径（且文件仍存在）
     * 2) 其次配置路径（磁盘文件）
     * 3) 再尝试源码资源路径（src/main/resources/python）
     * 4) 最后 classpath 提取到临时文件
     */
    private String resolveScriptPath(String configuredPath, String resourceName) throws IOException {
        // 1) 命中缓存且文件存在
        if (scriptPathCache.containsKey(resourceName)) {
            String cachedPath = scriptPathCache.get(resourceName);
            if (new File(cachedPath).exists()) {
                return cachedPath;
            }
            log.warn("脚本缓存路径已失效，将重新解析，resourceName={}, cachedPath={}", resourceName, cachedPath);
        }

        // 2) 使用配置路径（如果存在）
        if (configuredPath != null && !configuredPath.isEmpty()) {
            File file = new File(configuredPath);
            if (file.exists()) {
                scriptPathCache.put(resourceName, configuredPath);
                return configuredPath;
            }
        }

        // 3) 开发环境兜底：尝试 src/main/resources/python/<resourceName>
        File devResourceFile = new File("src/main/resources/python/" + resourceName);
        if (devResourceFile.exists()) {
            String devPath = devResourceFile.getPath();
            scriptPathCache.put(resourceName, devPath);
            log.info("脚本路径自动探测成功（源码资源路径），resourceName={}, path={}", resourceName, devPath);
            return devPath;
        }

        // 4) 从 classpath 提取到临时文件
        log.warn("配置脚本路径不存在: {}，且源码资源路径未找到，尝试从 classpath 加载", configuredPath);
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
