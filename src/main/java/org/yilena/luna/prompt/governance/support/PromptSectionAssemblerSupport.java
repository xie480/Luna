package org.yilena.luna.prompt.governance.support;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 提示词分段组装辅助类，负责把解析后的提示词槽位映射到运行时上下文章节，
 * 并输出快照引用、章节预览和最终拼装所需的中间结构。
 */
public final class PromptSectionAssemblerSupport {
    private static final String SECTION_INSTRUCTIONS = "Instructions";
    private static final String SECTION_CURRENT_TASK_STATE = "Current Task State";
    private static final String SECTION_RECONSTRUCTED_USER_INTENT = "Reconstructed User Intent";
    private static final String SECTION_RELEVANT_KNOWLEDGE_EVIDENCE = "Relevant Knowledge Evidence";
    private static final String SECTION_MCP_HINTS = "MCP Resource / Prompt Hints";
    private static final String SECTION_TOOL_EVIDENCE = "Tool Evidence";
    private static final String SECTION_RECENT_INTERACTION = "Recent Interaction Context";
    private static final String SECTION_MEMORY_HINTS = "Memory Hints";
    private static final String SECTION_OUTPUT_CONSTRAINTS = "Output Constraints";
    private static final String SLOT_RUNTIME_PROMPT = "runtime.prompt";
    private static final String FIELD_ITEM_ID = "itemId";
    private static final String FIELD_VERSION_ID = "versionId";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_PROMPT_ITEM_ID = "promptItemId";
    private static final String FIELD_PROMPT_ITEM_VERSION_ID = "promptItemVersionId";
    private static final String FIELD_PROMPT_KEY = "promptKey";
    private static final String FIELD_PROMPT_VERSION = "promptVersion";
    private static final String FILTER_REASON_UNKNOWN_SLOT = "UNKNOWN_RUNTIME_SLOT";
    private static final String FILTER_REASON_SECTION_EMPTY = "SECTION_EMPTY";
    private static final String FILTER_REASON_VALUE_NOT_RENDERED = "VALUE_NOT_RENDERED";

    private PromptSectionAssemblerSupport() {
    }

    // ... existing code ...

    /**
     * 将解析后的提示词槽位映射应用到分区内容中，实现动态变量替换。
     * <p>
     * 该方法的主要职责包括：
     * 1. 遍历槽位映射，跳过系统级槽位（instructions.system、runtime.prompt）
     * 2. 提取每个槽位对应的提示词值列表，过滤空值
     * 3. 将运行时槽位名称映射到目标分区名称（如memory_hints -> Memory Hints）
     * 4. 合并现有分区内容和新注入的提示词值，去重后更新分区
     * <p>
     * 此方法支持动态提示词注入，允许根据上下文动态添加记忆片段、知识证据等内容到指定分区。
     * 系统级槽位（instructions.system和runtime.prompt）由其他机制处理，此处跳过。
     *
     * @param sections    提示词分区映射，key为分区名称（如"Memory Hints"），value为该分区的文本行列表
     * @param slotMapping 槽位映射关系，key为槽位名称（如"memory_hints"），value为该槽位对应的解析提示词项列表
     */
    public static void applyResolvedPromptSlots(Map<String, List<String>> sections,
                                                Map<String, List<ResolvedPromptItem>> slotMapping) {
        if (sections == null || sections.isEmpty() || slotMapping == null || slotMapping.isEmpty()) {
            return;
        }

        // 遍历槽位映射，将动态提示词注入到对应分区
        for (Map.Entry<String, List<ResolvedPromptItem>> entry : slotMapping.entrySet()) {
            String slot = entry.getKey();
            // 跳过无效槽位和系统级槽位
            if (slot == null || slot.isBlank() || "instructions.system".equalsIgnoreCase(slot) || "runtime.prompt".equalsIgnoreCase(slot)) {
                continue;
            }

            // 提取槽位对应的提示词值列表，过滤空值
            List<String> values = entry.getValue().stream()
                    .map(ResolvedPromptItem::getValue)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            if (values.isEmpty()) {
                continue;
            }

            // 将运行时槽位映射到目标分区，合并现有内容和新注入的内容
            String targetSection = mapRuntimeSlotToSection(slot);
            if (targetSection != null) {
                sections.put(targetSection, mergeDistinct(sections.getOrDefault(targetSection, List.of()), values));
            }
        }
    }

    // ... existing code ...


    public static Map<String, List<String>> buildSectionPreview(Map<String, List<ResolvedPromptItem>> slotMapping) {
        Map<String, List<String>> sections = initRuntimeSections();
        sections.put(SECTION_INSTRUCTIONS, slotValues(slotMapping, "instructions.system"));
        applyResolvedPromptSlots(sections, slotMapping);
        return sections;
    }

    public static String joinSlotValues(List<ResolvedPromptItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        return items.stream()
                .map(ResolvedPromptItem::getValue)
                .filter(value -> value != null && !value.isBlank())
                .reduce("", (a, b) -> a.isBlank() ? b : a + "\n\n" + b);
    }

    public static Map<String, String> toAssembledText(Map<String, List<String>> sections) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sections == null || sections.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            String joined = entry.getValue() == null ? "" : entry.getValue().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .reduce("", (a, b) -> a.isBlank() ? b : a + "\n\n" + b);
            out.put(entry.getKey(), joined);
        }
        return out;
    }

    public static Map<String, Object> withPromptRefAliases(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(row);
        putIfMissing(out, FIELD_PROMPT_ITEM_ID, out.get(FIELD_ITEM_ID));
        putIfMissing(out, FIELD_PROMPT_ITEM_VERSION_ID, out.get(FIELD_VERSION_ID));
        putIfMissing(out, FIELD_PROMPT_KEY, safe(out.get(FIELD_KEY)));
        putIfMissing(out, FIELD_PROMPT_VERSION, safe(out.get(FIELD_VERSION)));
        return out;
    }

    public static List<Map<String, Object>> deduplicatePromptRefs(List<Map<String, Object>> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> ref : refs) {
            if (ref == null || ref.isEmpty()) {
                continue;
            }
            Map<String, Object> normalized = withPromptRefAliases(ref);
            String key = firstNonBlank(safe(normalized.get(FIELD_PROMPT_KEY)), safe(normalized.get(FIELD_KEY)));
            String runtimeSlot = safe(normalized.get("runtimeSlot"));
            Long versionId = firstNonNullLong(
                    toLong(normalized.get(FIELD_PROMPT_ITEM_VERSION_ID)),
                    toLong(normalized.get(FIELD_VERSION_ID))
            );
            String dedupeKey = key + "|" + (versionId == null ? "" : versionId) + "|" + runtimeSlot;
            deduped.putIfAbsent(dedupeKey, normalized);
        }
        return new ArrayList<>(deduped.values());
    }

    public static PromptRefFilterResult filterPromptRefsByFinalSections(List<Map<String, Object>> refs,
                                                                        Map<String, List<Map<String, Object>>> slotMapping,
                                                                        Map<String, List<String>> sections,
                                                                        Map<String, List<String>> canonicalSections) {
        List<Map<String, Object>> normalizedRefs = deduplicatePromptRefs(refs);
        if (normalizedRefs.isEmpty()) {
            return PromptRefFilterResult.empty();
        }
        boolean noSectionContext = (sections == null || sections.isEmpty())
                && (canonicalSections == null || canonicalSections.isEmpty());
        if (noSectionContext) {
            return new PromptRefFilterResult(normalizedRefs, normalizeSlotMapping(slotMapping), List.of());
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        List<Map<String, Object>> filteredOutItems = new ArrayList<>();
        for (Map<String, Object> ref : normalizedRefs) {
            PromptRefFilterDecision decision = evaluatePromptRefUsage(ref, sections, canonicalSections);
            if (decision.used()) {
                filtered.add(ref);
                continue;
            }
            Map<String, Object> filteredOut = new LinkedHashMap<>(ref);
            filteredOut.put("filteredReason", decision.reason());
            filteredOut.put("reason", decision.reason());
            filteredOut.put("targetSection", decision.targetSection());
            filteredOutItems.add(filteredOut);
        }
        Map<String, List<Map<String, Object>>> normalizedSlotMapping = normalizeSlotMapping(slotMapping);
        if (normalizedSlotMapping.isEmpty()) {
            return new PromptRefFilterResult(filtered, Map.of(), filteredOutItems);
        }
        Map<String, List<Map<String, Object>>> filteredSlotMapping = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : normalizedSlotMapping.entrySet()) {
            List<Map<String, Object>> filteredItems = entry.getValue().stream()
                    .map(PromptSectionAssemblerSupport::withPromptRefAliases)
                    .filter(row -> evaluatePromptRefUsage(row, sections, canonicalSections).used())
                    .toList();
            if (!filteredItems.isEmpty()) {
                filteredSlotMapping.put(entry.getKey(), filteredItems);
            }
        }
        return new PromptRefFilterResult(filtered, filteredSlotMapping, filteredOutItems);
    }

    public static String mapRuntimeSlotToSection(String slot) {
        String normalized = slot == null ? "" : slot.trim().toLowerCase();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.startsWith("instructions.")) {
            return SECTION_INSTRUCTIONS;
        }
        if ("memory.hints".equals(normalized) || normalized.startsWith("memory.")) {
            return SECTION_MEMORY_HINTS;
        }
        if ("output.constraints".equals(normalized) || normalized.startsWith("output.")) {
            return SECTION_OUTPUT_CONSTRAINTS;
        }
        if ("knowledge.evidence".equals(normalized) || normalized.startsWith("knowledge.")) {
            return SECTION_RELEVANT_KNOWLEDGE_EVIDENCE;
        }
        if (normalized.startsWith("task.state")) {
            return SECTION_CURRENT_TASK_STATE;
        }
        if (normalized.startsWith("intent.") || normalized.startsWith("reconstruction.")) {
            return SECTION_RECONSTRUCTED_USER_INTENT;
        }
        if (normalized.startsWith("mcp.resource") || normalized.startsWith("mcp.prompt") || normalized.startsWith("mcp.hint")) {
            return SECTION_MCP_HINTS;
        }
        if (normalized.startsWith("tool.evidence") || normalized.startsWith("mcp.tool")) {
            return SECTION_TOOL_EVIDENCE;
        }
        if (normalized.startsWith("recent.interaction") || normalized.startsWith("raw_input.") || "raw_input".equals(normalized)) {
            return SECTION_RECENT_INTERACTION;
        }
        return null;
    }

    private static List<String> slotValues(Map<String, List<ResolvedPromptItem>> slotMapping, String slot) {
        if (slotMapping == null || slotMapping.isEmpty()) {
            return List.of();
        }
        List<ResolvedPromptItem> items = slotMapping.getOrDefault(slot, List.of());
        if (items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(ResolvedPromptItem::getValue)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static List<String> mergeDistinct(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return new ArrayList<>(merged);
    }

    private static PromptRefFilterDecision evaluatePromptRefUsage(Map<String, Object> ref,
                                                                  Map<String, List<String>> sections,
                                                                  Map<String, List<String>> canonicalSections) {
        if (ref == null || ref.isEmpty()) {
            return PromptRefFilterDecision.filtered(FILTER_REASON_UNKNOWN_SLOT, "");
        }
        String slot = safe(ref.get("runtimeSlot"));
        String normalizedSlot = slot.isBlank() ? SLOT_RUNTIME_PROMPT : slot.trim().toLowerCase();
        if (SLOT_RUNTIME_PROMPT.equals(normalizedSlot)) {
            return PromptRefFilterDecision.used("");
        }
        String targetSection = mapRuntimeSlotToSection(normalizedSlot);
        if (targetSection == null || targetSection.isBlank()) {
            return PromptRefFilterDecision.filtered(FILTER_REASON_UNKNOWN_SLOT, "");
        }
        if (!hasSectionContent(targetSection, sections, canonicalSections)) {
            return PromptRefFilterDecision.filtered(FILTER_REASON_SECTION_EMPTY, targetSection);
        }
        String value = safe(ref.get("value"));
        if (value.isBlank()) {
            return PromptRefFilterDecision.used(targetSection);
        }
        if (sectionContainsPromptValue(targetSection, value, sections, canonicalSections)) {
            return PromptRefFilterDecision.used(targetSection);
        }
        return PromptRefFilterDecision.filtered(FILTER_REASON_VALUE_NOT_RENDERED, targetSection);
    }

    private static Map<String, List<Map<String, Object>>> normalizeSlotMapping(Map<String, List<Map<String, Object>>> slotMapping) {
        if (slotMapping == null || slotMapping.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : slotMapping.entrySet()) {
            String slot = safe(entry.getKey());
            if (slot.isBlank()) {
                continue;
            }
            List<Map<String, Object>> rows = entry.getValue() == null
                    ? List.of()
                    : entry.getValue().stream()
                    .filter(Objects::nonNull)
                    .map(PromptSectionAssemblerSupport::withPromptRefAliases)
                    .filter(row -> !row.isEmpty())
                    .toList();
            if (!rows.isEmpty()) {
                normalized.put(slot, rows);
            }
        }
        return normalized;
    }

    private static Map<String, List<String>> initRuntimeSections() {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put(SECTION_INSTRUCTIONS, List.of());
        sections.put(SECTION_CURRENT_TASK_STATE, List.of());
        sections.put(SECTION_RECONSTRUCTED_USER_INTENT, List.of());
        sections.put(SECTION_RELEVANT_KNOWLEDGE_EVIDENCE, List.of());
        sections.put(SECTION_MCP_HINTS, List.of());
        sections.put(SECTION_TOOL_EVIDENCE, List.of());
        sections.put(SECTION_RECENT_INTERACTION, List.of());
        sections.put(SECTION_MEMORY_HINTS, List.of());
        sections.put(SECTION_OUTPUT_CONSTRAINTS, List.of());
        return sections;
    }

    private static boolean sectionContainsPromptValue(String sectionName,
                                                      String promptValue,
                                                      Map<String, List<String>> sections,
                                                      Map<String, List<String>> canonicalSections) {
        if (promptValue == null || promptValue.isBlank()) {
            return true;
        }
        String normalizedPrompt = promptValue.trim();
        String sectionText = joinSectionText(sectionName, sections);
        if (!sectionText.isBlank() && sectionText.contains(normalizedPrompt)) {
            return true;
        }
        String canonicalText = joinSectionText(sectionName, canonicalSections);
        return !canonicalText.isBlank() && canonicalText.contains(normalizedPrompt);
    }

    private static boolean hasSectionContent(String sectionName,
                                             Map<String, List<String>> sections,
                                             Map<String, List<String>> canonicalSections) {
        return !joinSectionText(sectionName, sections).isBlank()
                || !joinSectionText(sectionName, canonicalSections).isBlank();
    }

    private static String joinSectionText(String sectionName, Map<String, List<String>> sections) {
        if (sectionName == null || sectionName.isBlank() || sections == null || sections.isEmpty()) {
            return "";
        }
        List<String> lines = sections.getOrDefault(sectionName, List.of());
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return lines.stream()
                .filter(line -> line != null && !line.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + "\n\n" + right);
    }

    private static void putIfMissing(Map<String, Object> row, String field, Object value) {
        if (row.containsKey(field)) {
            return;
        }
        row.put(field, value);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback == null ? "" : fallback;
    }

    private static Long firstNonNullLong(Long first, Long fallback) {
        return first != null ? first : fallback;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 提示词引用过滤结果，汇总保留引用、分槽映射以及被剔除的条目。
     */
    public record PromptRefFilterResult(List<Map<String, Object>> promptRefs,
                                        Map<String, List<Map<String, Object>>> slotMapping,
                                        List<Map<String, Object>> filteredOutItems) {
        public static PromptRefFilterResult empty() {
            return new PromptRefFilterResult(List.of(), Map.of(), List.of());
        }
    }

    /**
     * 提示词引用过滤决策，标记单条引用是否被采用及其去向或过滤原因。
     */
    private record PromptRefFilterDecision(boolean used, String reason, String targetSection) {
        private static PromptRefFilterDecision used(String targetSection) {
            return new PromptRefFilterDecision(true, "", targetSection == null ? "" : targetSection);
        }

        private static PromptRefFilterDecision filtered(String reason, String targetSection) {
            return new PromptRefFilterDecision(false, reason, targetSection == null ? "" : targetSection);
        }
    }
}
