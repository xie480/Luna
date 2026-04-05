package org.yilena.luna.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/*
    prompt缁勮鍣?
 */
@Component
@Deprecated(since = "2026.04", forRemoval = false)
public final class PromptAssembler {
    // 鎬讳綋prompt鍖哄潡鏈€澶у瓧绗︽暟
    private static final int MAX_PROMPT_CHARS = 60000;
    private static final String LEGACY_FLAG = "luna.allowLegacyPromptAssembler";

    /*
        鏍稿績缁勮閫昏緫 (鏃犵煡璇嗗簱)
     */
    public String assemble(List<String> memorySnippets, String userInput) {
        ensureLegacyUseAllowed();
        return assembleFinalPrompt(memorySnippets, null, null, null, null, userInput);
    }

    /*
        鏍稿績缁勮閫昏緫 (鍖呭惈鐭ヨ瘑搴?RAG 鍜?Tool Context)
     */
    public String assembleFinalPrompt(List<String> memorySnippets, List<String> knowledgeSnippets, String toolContext, String userInput) {
        ensureLegacyUseAllowed();
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
        ensureLegacyUseAllowed();
        Objects.requireNonNull(userInput, "userInput must not be null");
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // 1. System Prompt
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // 2. Knowledge Base Prompt (RAG 涓婁笅鏂?
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

        // 5. 宸ュ叿鎵ц缁撴灉 (Tool Context)
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
        鏋勫缓鍘嬬缉鎻愮ず璇?
     */
    public String buildSummaryPrompt(List<String> memorySnippets) {
        ensureLegacyUseAllowed();
        if (memorySnippets == null || memorySnippets.isEmpty()) {
            return "";
        }
        String merged = merge(memorySnippets);
        return PromptTemplates.SUMMARY_PROMPT.replace("{{MEMORY_SNIPPETS}}", merged);
    }

    public String assembleStartupPrompt(List<String> recentMemory) {
        ensureLegacyUseAllowed();
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

    private void ensureLegacyUseAllowed() {
        if (Boolean.parseBoolean(System.getProperty(LEGACY_FLAG, "false"))) {
            return;
        }
        throw new IllegalStateException("PromptAssembler is deprecated and blocked for production mainline. Use ContextAssembler instead.");
    }
}

