package org.yilena.runa.prompt;

import org.springframework.stereotype.Component;
import org.yilena.runa.constants.ModelHintConstant;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/*
    prompt组装器
 */
@Component
public final class PromptAssembler {

    // memory区块最大字符数
    private static final int MAX_MEMORY_CHARS = 1500;
    // 总体prompt区块最大字符数
    private static final int MAX_PROMPT_CHARS = 8000;

    /*
        核心组装逻辑
     */
    public String assemble(List<String> memorySnippets, String sceneTag, String emotionHint, String userInput) {
        // 输入检查
        Objects.requireNonNull(userInput, "用户输入为空");
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // System Prompt
        prompt.append(PromptTemplates.SYSTEM_PROMPT).append("\n\n");

        // Memory Prompt
        String memoryBlock = buildMemoryBlock(memorySnippets);
        if (!memoryBlock.isEmpty()) {
            prompt.append(memoryBlock).append("\n\n");
        }else{
            prompt.append(PromptTemplates.MEMORY_PROMPT).append("\n\n");
        }

        // Runtime Prompt
        String runtimePrompt = PromptTemplates.RUNTIME_PROMPT.formatted(
                safe(sceneTag),
                safe(emotionHint),
                userInput.trim()
        );
        prompt.append(runtimePrompt);

        return prompt.toString();
    }

    /*
        构建Memory Prompt
     */
    private String buildMemoryBlock(List<String> memorySnippets) {
        if (memorySnippets == null || memorySnippets.isEmpty()) {
            return "";
        }

        // 合并memory
        String merged = memorySnippets.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
        // 裁剪
        String trimmed = trimToMaxChars(merged, MAX_MEMORY_CHARS);
        // 如果模板包含占位符则替换，否则将trimmed拼接到模板末尾
        String template = PromptTemplates.MEMORY_PROMPT;
        if (template.contains("{{MEMORY_SNIPPETS}}")) {
            return template.replace("{{MEMORY_SNIPPETS}}", trimmed);
        } else {
            // 在模板后追加一个明确的分隔与记忆片段
            return template + "\n\n--- 记忆片段（按相关性排序） ---\n" + trimmed;
        }
    }

    /*
        截断字符串，并添加一个提示
     */
    private String trimToMaxChars(String input, int maxChars) {
        // 输入检查
        if (input == null || input.isEmpty() || input.length() <= maxChars) {
            return input == null ? "" : input;
        }
        // 截断
        return input.substring(0, maxChars) + "\n…（记忆已截断）";
    }

    /*
        确保字符串非空
     */
    private String safe(String value) {
        return (value == null || value.isBlank()) ? ModelHintConstant.UNSPECIFIED : value;
    }
}
