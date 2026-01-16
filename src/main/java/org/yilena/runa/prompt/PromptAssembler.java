package org.yilena.runa.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/*
    prompt组装器
 */
@Component
public final class PromptAssembler {
    // 总体prompt区块最大字符数
    private static final int MAX_PROMPT_CHARS = 15000;

    /*
        核心组装逻辑
     */
    public String assemble(List<String> memorySnippets, String userInput) {
        // 输入检查
        Objects.requireNonNull(userInput, "用户输入为空");
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // System Prompt
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // Memory Prompt
        appendMemoryIfPresent(prompt, memorySnippets);

        // Runtime Prompt
        String runtimePrompt = PromptTemplates.RUNTIME_PROMPT.formatted(userInput.trim());
        prompt.append(runtimePrompt);

        return prompt.toString();
    }

    private void appendMemoryIfPresent(StringBuilder prompt, List<String> memorySnippets) {
        if (memorySnippets == null || memorySnippets.isEmpty()) {
            return;
        }
        String merged = merge(memorySnippets);
        if (!merged.isEmpty()) {
            String memoryBlock = PromptTemplates.MEMORY_PROMPT.replace("{{MEMORY_SNIPPETS}}", merged);
            append(prompt, memoryBlock);
        }
    }

    private String merge(List<String> snippets) {
        String merged = snippets.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
        return merged;
    }


    private void append(StringBuilder sb, String block) {
        if (block != null && !block.isBlank()) {
            sb.append(block).append("\n\n");
        }
    }

    /*
        构建压缩提示词
     */
    public String buildSummaryPrompt(List<String> memorySnippets) {
        if (memorySnippets == null || memorySnippets.isEmpty()) {
            return "";
        }
        String merged = merge(memorySnippets);
        return PromptTemplates.SUMMARY_PROMPT.replace("{{MEMORY_SNIPPETS}}", merged);
    }

    public String assembleStartupPrompt(List<String> recentMemory) {
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // System Prompt
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // Memory Prompt
        appendMemoryIfPresent(prompt, recentMemory);

        // Startup Prompt
        String startup = PromptTemplates.STARTUP_PROMPT
                .formatted(LocalTime.now().toString());
        append(prompt, startup);

        return prompt.toString();
    }
}
