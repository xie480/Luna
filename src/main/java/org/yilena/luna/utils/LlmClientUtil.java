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
import org.yilena.luna.properties.EmbeddingProperty;
import org.yilena.luna.properties.GeminiProperty;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 模型调用工具类
 * 已重构为基于 LangChain4j 实现，支持多模态及更优雅的 API 调用
 * 【v2.0】已移除舊的 Tool Router 依賴，專注於純粹的模型生成與 Embedding
 */
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

    // 缓存解压后的临时脚本路径，避免每次请求都重复解压
    // Key: scriptName (e.g., "embedding.py"), Value: absolute path
    private static final Map<String, String> scriptPathCache = new ConcurrentHashMap<>();

    /**
     * 统一的模型生成入口 (無工具支持，用於主腦生成或修復)
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

            // 根据请求的模型名称匹配对应的配置（URL 和 API Key）
            String requestModelName = request.getModelName();
            GeminiProperty.ModelConfig config = getModelConfig(requestModelName);
            
            if (config == null) {
                log.error("未找到模型名称 [{}] 对应的配置信息，请检查 application.yaml", requestModelName);
                return null;
            }

            String baseUrl = config.getUrl();
            if (baseUrl != null) {
                baseUrl = baseUrl.replace("/chat/completions", "")
                        .replace("/embeddings", "");
            }

            // 动态构建 ChatModel，以便支持每次请求不同的 temperature 和 modelName
            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(config.getApiKey())
                    .modelName(requestModelName)
                    .temperature(request.getTemperature() != null ? request.getTemperature() : 0.7)
                    .timeout(Duration.ofSeconds(120)) // 縮短超時時間
                    .maxRetries(3) // 增加重試
                    .logRequests(true) // 開啟日誌
                    .logResponses(true)
                    .build();

            Response<AiMessage> response = chatModel.generate(messages);
            return LlmResponse.builder().content(response.content().text()).build();

        } catch (Exception e) {
            log.error("调用模型异常，model={}: {}", request.getModelName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据模型名称查找对应的配置
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
        
        // 如果找不到匹配的，默认使用 Big Model 的配置（或者根据策略调整）
        log.warn("请求的模型 [{}] 未在配置中找到精确匹配，将默认使用 Big Model 的 URL 和 Key", modelName);
        return geminiProperty.getBig();
    }

    /**
     * 获取文本 Embedding
     */
    public String getEmbedding(String text) throws Exception {
        String pythonPath = embeddingProperty.getPythonPath();
        // 自动解析脚本路径
        String scriptPath = resolveScriptPath(embeddingProperty.getScriptPath(), "embedding.py");
        String modelPath = embeddingProperty.getModelPath();

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                modelPath, // 传递模型路径
                text       // 传递文本
        );

        Process process = pb.start();

        // 读取标准输出 (stdout)
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        // 读取标准错误 (stderr)
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

        return result;
    }

    /**
     * 执行 Rerank (重排序)
     *
     * @param query     查询语句
     * @param documents 待排序的文档列表
     * @return 文档对应的分数列表 (List<Double>)，顺序与输入 documents 一致
     */
    public List<Double> rerank(String query, List<String> documents) throws Exception {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        if (rerankModelPath == null || rerankModelPath.isEmpty()) {
            throw new IllegalStateException("Rerank 模型路径未配置 (rerank.model-path)");
        }

        // 复用 Embedding 的 Python 环境
        String pythonPath = embeddingProperty.getPythonPath();
        String scriptPath = resolveScriptPath(rerankScriptPath, "rerank.py");

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                rerankModelPath
        );

        Process process = pb.start();

        // 通过 Stdin 发送 JSON 数据 (避免命令行参数过长)
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Map<String, Object> inputPayload = new HashMap<>();
            inputPayload.put("query", query);
            inputPayload.put("documents", documents);

            String jsonInput = objectMapper.writeValueAsString(inputPayload);
            writer.write(jsonInput);
            writer.flush();
        }

        // 读取标准输出 (stdout)
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        // 读取标准错误 (stderr)
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

        // 解析返回的 JSON 分数列表
        return objectMapper.readValue(result, objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
    }

    /**
     * 解析 Python 脚本路径
     * 1. 优先使用配置的绝对路径
     * 2. 如果文件不存在，尝试从 Classpath (resources/python/{resourceName}) 加载并复制到临时文件
     * 3. 增加缓存机制，避免重复解压
     */
    private String resolveScriptPath(String configuredPath, String resourceName) throws IOException {
        // 1. 检查缓存
        if (scriptPathCache.containsKey(resourceName)) {
            String cachedPath = scriptPathCache.get(resourceName);
            if (new File(cachedPath).exists()) {
                return cachedPath;
            }
        }

        // 2. 检查配置的物理路径
        if (configuredPath != null && !configuredPath.isEmpty()) {
            File file = new File(configuredPath);
            if (file.exists()) {
                scriptPathCache.put(resourceName, configuredPath);
                return configuredPath;
            }
            log.warn("配置的脚本路径不存在: {}，将尝试从 Classpath 加载", configuredPath);
        }

        // 3. 尝试从 Classpath 加载
        String resourcePath = "python/" + resourceName;
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new FileNotFoundException("无法在磁盘或 Classpath 中找到 " + resourceName + "。配置路径: " + configuredPath);
            }

            // 创建临时文件
            String prefix = "luna_" + resourceName.replace(".py", "") + "_";
            File tempFile = File.createTempFile(prefix, ".py");
            tempFile.deleteOnExit(); // 程序退出时自动删除

            // 将资源文件复制到临时文件
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            log.info("已从 Classpath 提取脚本 [{}] 到临时文件: {}", resourceName, tempFile.getAbsolutePath());
            String tempPath = tempFile.getAbsolutePath();
            scriptPathCache.put(resourceName, tempPath);
            return tempPath;
        }
    }
}
