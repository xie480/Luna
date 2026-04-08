package org.yilena.luna.prompt.governance.support;

import org.yilena.luna.prompt.governance.model.ResolvedPromptItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class PromptSectionAssemblerSupport {

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
            if (slot.startsWith("instructions.")) {
                sections.put("Instructions", mergeDistinct(sections.getOrDefault("Instructions", List.of()), values));
            } else if ("memory.hints".equalsIgnoreCase(slot)) {
                sections.put("Memory Hints", mergeDistinct(sections.getOrDefault("Memory Hints", List.of()), values));
            } else if ("output.constraints".equalsIgnoreCase(slot)) {
                sections.put("Output Constraints", mergeDistinct(sections.getOrDefault("Output Constraints", List.of()), values));
            } else if ("knowledge.evidence".equalsIgnoreCase(slot)) {
                sections.put("Relevant Knowledge Evidence", mergeDistinct(sections.getOrDefault("Relevant Knowledge Evidence", List.of()), values));
            }
        }
    }

    public static Map<String, List<String>> buildSectionPreview(Map<String, List<ResolvedPromptItem>> slotMapping) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("Instructions", slotValues(slotMapping, "instructions.system"));
        sections.put("Relevant Knowledge Evidence", slotValues(slotMapping, "knowledge.evidence"));
        sections.put("Memory Hints", slotValues(slotMapping, "memory.hints"));
        sections.put("Output Constraints", slotValues(slotMapping, "output.constraints"));
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
}
