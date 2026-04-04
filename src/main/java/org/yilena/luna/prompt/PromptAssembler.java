package org.yilena.luna.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/*
    prompt组装器
 */
@Component
@Deprecated(since = "2026.04", forRemoval = false)
public final class PromptAssembler {
    // 总体prompt区块最大字符数
    private static final int MAX_PROMPT_CHARS = 60000;

    /*
        核心组装逻辑 (无知识库)
     */
    public String assemble(List<String> memorySnippets, String userInput) {
        return assembleFinalPrompt(memorySnippets, null, null, null, null, userInput);
    }

    /*
        核心组装逻辑 (包含知识库 RAG 和 Tool Context)
     */
    public String assembleFinalPrompt(List<String> memorySnippets, List<String> knowledgeSnippets, String toolContext, String userInput) {
        return assembleFinalPrompt(memorySnippets, knowledgeSnippets, null, null, toolContext, userInput);
    }

    /*
        Core assembly logic with explicit preference + long-term-memory injection.
     */
    public String assembleFinalPrompt(
            List<String> memorySnippets,
            List<String> knowledgeSnippets,
            List<String> preferenceSnippets,
            List<String> longTermMemorySnippets,
            String toolContext,
            String userInput
    ) {
        // 输入检查
        Objects.requireNonNull(userInput, "用户输入为空");
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // 1. System Prompt
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // 2. Knowledge Base Prompt (RAG 上下文)
        if (knowledgeSnippets != null && !knowledgeSnippets.isEmpty()) {
            String kbMerged = merge(knowledgeSnippets);
            if (!kbMerged.isEmpty()) {
                String kbBlock = String.format(PromptTemplates.KNOWLEDGE_BASE_PROMPT, kbMerged);
                append(prompt, kbBlock);
            }
        }

        // 3. Preference Prompt
        appendPreferenceIfPresent(prompt, preferenceSnippets);

        // 4. Long-term Memory Prompt
        appendLongTermMemoryIfPresent(prompt, longTermMemorySnippets);

        // 5. 工具执行结果 (Tool Context)
        if (toolContext != null && !toolContext.isBlank()) {
            append(prompt, PromptTemplates.TOOL_CONTEXT_PROMPT.formatted(toolContext));
        }

        // 6. Memory Prompt
        appendMemoryIfPresent(prompt, memorySnippets);

        // 7. Runtime Prompt
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
        return snippets.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    private void appendPreferenceIfPresent(StringBuilder prompt, List<String> preferenceSnippets) {
        if (preferenceSnippets == null || preferenceSnippets.isEmpty()) {
            return;
        }
        String merged = merge(preferenceSnippets);
        if (!merged.isEmpty()) {
            append(prompt, PromptTemplates.PREFERENCE_PROMPT.formatted(merged));
        }
    }

    private void appendLongTermMemoryIfPresent(StringBuilder prompt, List<String> longTermMemorySnippets) {
        if (longTermMemorySnippets == null || longTermMemorySnippets.isEmpty()) {
            return;
        }
        String merged = merge(longTermMemorySnippets);
        if (!merged.isEmpty()) {
            append(prompt, PromptTemplates.LONG_TERM_MEMORY_PROMPT.formatted(merged));
        }
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
