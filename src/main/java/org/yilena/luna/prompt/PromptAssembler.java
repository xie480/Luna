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
public final class PromptAssembler {
    // 总体prompt区块最大字符数
    private static final int MAX_PROMPT_CHARS = 60000;

    /*
        核心组装逻辑 (无知识库)
     */
    public String assemble(List<String> memorySnippets, String userInput) {
        return assembleFinalPrompt(memorySnippets, null, null, userInput);
    }

    /*
        核心组装逻辑 (包含知识库 RAG 和 Tool Context)
     */
    public String assembleFinalPrompt(List<String> memorySnippets, List<String> knowledgeSnippets, String toolContext, String userInput) {
        // 输入检查
        Objects.requireNonNull(userInput, "用户输入为空");
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // 1. System Prompt
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // 2. Knowledge Base Prompt (RAG 上下文)
        if (knowledgeSnippets != null && !knowledgeSnippets.isEmpty()) {
            String kbMerged = merge(knowledgeSnippets);
            if (!kbMerged.isEmpty()) {
                String kbBlock = "【本地知识库检索结果】\n" + kbMerged + "\n\n请优先参考以上知识库内容回答用户的问题。如果知识库内容与问题无关，请按照你的正常逻辑回答。";
                append(prompt, kbBlock);
            }
        }

        // 3. 工具执行结果 (Tool Context)
        if (toolContext != null && !toolContext.isBlank()) {
            append(prompt, PromptTemplates.TOOL_CONTEXT_PROMPT.formatted(toolContext));
        }

        // 4. Memory Prompt
        appendMemoryIfPresent(prompt, memorySnippets);

        // 5. Runtime Prompt
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
