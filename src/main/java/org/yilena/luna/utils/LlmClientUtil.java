package org.yilena.luna.utils;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.yilena.luna.config.LunaAgentConfig;
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
import java.util.List;

/**
 * LLM 模型调用工具类
 * 已重构为基于 LangChain4j 实现，支持多模态及更优雅的 API 调用
 * 包含 Tool Agent 的调用封装
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientUtil {

    private final GeminiProperty geminiProperty;
    private final EmbeddingProperty embeddingProperty;

    // 使用 ObjectProvider 延迟获取 Agent，解决循环依赖问题
    // LunaAgentConfig -> Tools -> Service -> LlmClientUtil -> LunaAgentConfig
    private final ObjectProvider<LunaAgentConfig.LunaToolAgent> toolAgentProvider;

    // 缓存解压后的临时脚本路径，避免每次请求都重复解压
    private static volatile String cachedScriptPath;

    /**
     * 調用帶有工具支持的 Agent 進行對話
     *
     * @param prompt 提示詞
     * @return 模型返回的 JSON 字符串，如果失敗則返回 null
     */
    public String chatWithTools(String prompt) {
        try {
            log.info("正在調用 LunaToolAgent (包含工具支持)...");
            LunaAgentConfig.LunaToolAgent agent = toolAgentProvider.getIfAvailable();
            if (agent == null) {
                log.error("LunaToolAgent 未初始化或不可用");
                return null;
            }
            // 直接傳入 String，LunaToolAgent 接口已通過註解確保安全轉義
            return agent.chat(prompt);
        } catch (Exception e) {
            log.error("LunaToolAgent 調用異常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 统一的模型生成入口 (無工具支持，用於簡單生成或修復)
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
                    .timeout(Duration.ofSeconds(600))
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
        // 自动解析脚本路径：如果配置路径不存在，则尝试从资源文件加载
        String scriptPath = resolveScriptPath(embeddingProperty.getScriptPath());
        String modelPath = embeddingProperty.getModelPath();

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath,
                scriptPath,
                modelPath, // 传递模型路径
                text       // 传递文本
        );

        // 合并错误流到标准输出流，或者分别读取。这里选择分别读取以便区分错误。
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
     * 解析 Python 脚本路径
     * 1. 优先使用配置的绝对路径
     * 2. 如果文件不存在，尝试从 Classpath (resources/python/embedding.py) 加载并复制到临时文件
     * 3. 增加缓存机制，避免重复解压
     */
    private String resolveScriptPath(String configuredPath) throws IOException {
        // 1. 检查缓存
        if (cachedScriptPath != null && new File(cachedScriptPath).exists()) {
            return cachedScriptPath;
        }

        // 2. 检查配置的物理路径
        File file = new File(configuredPath);
        if (file.exists()) {
            cachedScriptPath = configuredPath;
            return configuredPath;
        }

        // 3. 尝试从 Classpath 加载
        // 注意：getResourceAsStream 的路径不需要以 / 开头，相对于 classpath 根目录
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream("python/embedding.py")) {
            if (is == null) {
                log.warn("配置的脚本路径不存在: {}，且无法在 Classpath 中找到 python/embedding.py", configuredPath);
                throw new FileNotFoundException("无法在磁盘或 Classpath 中找到 embedding.py。配置路径: " + configuredPath);
            }

            // 创建临时文件
            File tempFile = File.createTempFile("luna_embedding_", ".py");
            tempFile.deleteOnExit(); // 程序退出时自动删除

            // 将资源文件复制到临时文件
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            log.info("已从 Classpath 提取脚本到临时文件: {}", tempFile.getAbsolutePath());
            cachedScriptPath = tempFile.getAbsolutePath();
            return cachedScriptPath;
        }
    }
}
