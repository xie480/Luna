package org.yilena.luna.prompt.governance.support;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    private PromptSectionAssemblerSupport() {
    }

    public static void applyResolvedPromptSlots(Map<String, List<String>> sections,
                                                Map<String, List<ResolvedPromptItem>> slotMapping) {
        if (sections == null || sections.isEmpty() || slotMapping == null || slotMapping.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<ResolvedPromptItem>> entry : slotMapping.entrySet()) {
            String slot = entry.getKey();
            if (slot == null || slot.isBlank() || "instructions.system".equalsIgnoreCase(slot) || "runtime.prompt".equalsIgnoreCase(slot)) {
                continue;
            }
            List<String> values = entry.getValue().stream()
                    .map(ResolvedPromptItem::getValue)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            if (values.isEmpty()) {
                continue;
            }
            String targetSection = mapRuntimeSlotToSection(slot);
            if (targetSection != null) {
                sections.put(targetSection, mergeDistinct(sections.getOrDefault(targetSection, List.of()), values));
            }
        }
    }

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

    private static String mapRuntimeSlotToSection(String slot) {
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
}
