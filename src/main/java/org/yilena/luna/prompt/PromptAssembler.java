package org.yilena.luna.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Deprecated(since = "2026.04", forRemoval = false)
/**
 * 旧版提示词组装器，负责按固定模板拼接记忆、知识和工具上下文生成完整提示词，
 * 当前主要用于兼容旧链路，主流程应优先使用新的上下文组装能力。
 */
public final class PromptAssembler {
    /**
     * 单个提示词允许构建的最大字符数，用于限制旧版拼装链路的上下文体积。
     */
    private static final int MAX_PROMPT_CHARS = 60000;
    /**
     * 控制是否允许继续使用旧版组装器的兼容开关。
     */
    private static final String LEGACY_FLAG = "luna.allowLegacyPromptAssembler";

    /**
     * 基于记忆片段和用户输入组装最基础的旧版提示词。
     */
    public String assemble(List<String> memorySnippets, String userInput) {
        ensureLegacyUseAllowed();
        return assembleFinalPrompt(memorySnippets, null, null, null, null, userInput);
    }

    /**
     * 在基础提示词上额外注入知识片段和工具上下文，兼容旧版 RAG 与工具链路。
     */
    public String assembleFinalPrompt(List<String> memorySnippets, List<String> knowledgeSnippets, String toolContext, String userInput) {
        ensureLegacyUseAllowed();
        return assembleFinalPrompt(memorySnippets, knowledgeSnippets, null, null, toolContext, userInput);
    }

    /**
     * 核心组装逻辑，支持显式注入偏好记忆与长期记忆。
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

        // 1. 先写入系统提示词，定义基础人设与输出约束。
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // 2. 注入知识库检索结果，让模型优先参考外部知识证据。
        if (knowledgeSnippets != null && !knowledgeSnippets.isEmpty()) {
            String kbMerged = merge(knowledgeSnippets);
            if (!kbMerged.isEmpty()) {
                String kbBlock = String.format(PromptTemplates.KNOWLEDGE_BASE_PROMPT, kbMerged);
                append(prompt, kbBlock);
            }
        }

        // 3. 注入用户偏好，约束称呼、语气和表达习惯。
        appendPreferenceIfPresent(prompt, preferenceSnippets);

        // 4. 注入长期记忆，补齐跨轮次保留的重要事实。
        appendLongTermMemoryIfPresent(prompt, longTermMemorySnippets);

        // 5. 注入工具上下文，使模型能够承接最近工具执行结果。
        if (toolContext != null && !toolContext.isBlank()) {
            append(prompt, PromptTemplates.TOOL_CONTEXT_PROMPT.formatted(toolContext));
        }

        // 6. 注入近期记忆片段，补充当前轮次前的对话线索。
        appendMemoryIfPresent(prompt, memorySnippets);

        // 7. 最后拼接本轮用户输入，形成最终运行提示词。
        String runtimePrompt = PromptTemplates.RUNTIME_PROMPT.formatted(userInput.trim());
        prompt.append(runtimePrompt);

        return prompt.toString();
    }

    /**
     * 当存在近期记忆时，将记忆片段整理后注入记忆提示块。
     */
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

    /**
     * 合并片段列表，过滤空值后按双换行拼接。
     */
    private String merge(List<String> snippets) {
        return snippets.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 当存在偏好片段时，将其整理后写入偏好提示块。
     */
    private void appendPreferenceIfPresent(StringBuilder prompt, List<String> preferenceSnippets) {
        if (preferenceSnippets == null || preferenceSnippets.isEmpty()) {
            return;
        }
        String merged = merge(preferenceSnippets);
        if (!merged.isEmpty()) {
            append(prompt, PromptTemplates.PREFERENCE_PROMPT.formatted(merged));
        }
    }

    /**
     * 当存在长期记忆片段时，将其整理后写入长期记忆提示块。
     */
    private void appendLongTermMemoryIfPresent(StringBuilder prompt, List<String> longTermMemorySnippets) {
        if (longTermMemorySnippets == null || longTermMemorySnippets.isEmpty()) {
            return;
        }
        String merged = merge(longTermMemorySnippets);
        if (!merged.isEmpty()) {
            append(prompt, PromptTemplates.LONG_TERM_MEMORY_PROMPT.formatted(merged));
        }
    }

    /**
     * 以统一格式向提示词缓冲区追加非空片段。
     */
    private void append(StringBuilder sb, String block) {
        if (block != null && !block.isBlank()) {
            sb.append(block).append("\n\n");
        }
    }

    /**
     * 基于近期记忆构建旧版摘要提示词。
     */
    public String buildSummaryPrompt(List<String> memorySnippets) {
        ensureLegacyUseAllowed();
        if (memorySnippets == null || memorySnippets.isEmpty()) {
            return "";
        }
        String merged = merge(memorySnippets);
        return PromptTemplates.SUMMARY_PROMPT.replace("{{MEMORY_SNIPPETS}}", merged);
    }

    /**
     * 基于近期记忆构建系统启动阶段的旧版提示词。
     */
    public String assembleStartupPrompt(List<String> recentMemory) {
        ensureLegacyUseAllowed();
        StringBuilder prompt = new StringBuilder(MAX_PROMPT_CHARS);

        // 先写入系统提示词，确保启动阶段仍遵守统一人设与约束。
        append(prompt, PromptTemplates.SYSTEM_PROMPT);

        // 补充近期记忆，让启动问候可以承接上次会话状态。
        appendMemoryIfPresent(prompt, recentMemory);

        // 追加启动提示块，注入当前时间等启动场景信息。
        String startup = PromptTemplates.STARTUP_PROMPT
                .formatted(LocalTime.now().toString());
        append(prompt, startup);

        return prompt.toString();
    }

    /**
     * 校验是否允许继续使用旧版组装器，未开启兼容开关时直接阻断。
     */
    private void ensureLegacyUseAllowed() {
        if (Boolean.parseBoolean(System.getProperty(LEGACY_FLAG, "false"))) {
            return;
        }
        throw new IllegalStateException("PromptAssembler is deprecated and blocked for production mainline. Use ContextAssembler instead.");
    }
}
