package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.InputReconstructionResult;

import java.util.ArrayList;
import java.util.List;

@Value
@Builder
public class GovernedSignal {
    boolean debugFlag;
    String intent;
    String goal;
    List<String> constraints;
    String timeScope;
    List<String> missingSlots;
    String fallback;

    public static GovernedSignal fromReconstruction(String rawInput, InputReconstructionResult reconstruction) {
        boolean debug = containsDebugFlag(rawInput);
        if (reconstruction == null) {
            return GovernedSignal.builder()
                    .debugFlag(debug)
                    .intent("intent_unavailable")
                    .goal("goal_unavailable")
                    .constraints(List.of())
                    .timeScope("unspecified")
                    .missingSlots(List.of("reconstruction_missing"))
                    .fallback("reconstruct_retry_required")
                    .build();
        }
        String intent = normalize(reconstruction.getNormalizedUserIntent(), "intent_unavailable");
        String goal = normalize(reconstruction.getExplicitTaskGoal(), "goal_unavailable");
        String timeScope = normalize(reconstruction.getTimeScope(), "unspecified");
        List<String> constraints = normalizeList(reconstruction.getBusinessConstraints());
        List<String> missingSlots = normalizeList(reconstruction.getMissingSlots());
        String fallback = (isUnavailable(intent, "intent_unavailable") || isUnavailable(goal, "goal_unavailable"))
                ? "reconstruct_retry_required"
                : "none";
        return GovernedSignal.builder()
                .debugFlag(debug)
                .intent(intent)
                .goal(goal)
                .constraints(constraints)
                .timeScope(timeScope)
                .missingSlots(missingSlots)
                .fallback(fallback)
                .build();
    }

    public static GovernedSignal fromRawInput(String rawInput) {
        return GovernedSignal.builder()
                .debugFlag(containsDebugFlag(rawInput))
                .intent("intent_unavailable")
                .goal("goal_unavailable")
                .constraints(List.of())
                .timeScope("unspecified")
                .missingSlots(List.of("reconstruction_missing"))
                .fallback("reconstruct_retry_required")
                .build();
    }

    public List<String> safeConstraints() {
        return normalizeList(constraints);
    }

    public List<String> safeMissingSlots() {
        return normalizeList(missingSlots);
    }

    private static boolean containsDebugFlag(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return input.contains("#rag_debug") || input.contains("/rag_debug");
    }

    private static String normalize(String value, String fallbackValue) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() ? fallbackValue : text;
    }

    private static boolean isUnavailable(String value, String unavailableFlag) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return unavailableFlag.equalsIgnoreCase(value.trim());
    }

    private static List<String> normalizeList(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : source) {
            if (item == null) {
                continue;
            }
            String cleaned = item.trim();
            if (!cleaned.isBlank()) {
                out.add(cleaned);
            }
        }
        return out.stream().distinct().toList();
    }
}
