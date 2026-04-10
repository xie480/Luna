package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.InputReconstructionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 该模型用于把输入重构结果转换为受治理的信号摘要，供记忆与编排模块统一读取。
 */
@Value
@Builder
public class GovernedSignal {
    /**
     * 是否显式携带调试标记。
     */
    boolean debugFlag;
    /**
     * 归一化后的意图信号。
     */
    String intent;
    /**
     * 提炼出的目标信号。
     */
    String goal;
    /**
     * 业务约束信号集合。
     */
    List<String> constraints;
    /**
     * 时间范围信号。
     */
    String timeScope;
    /**
     * 尚未补齐的槽位集合。
     */
    List<String> missingSlots;
    /**
     * 当前信号不足时的降级指令。
     */
    String fallback;

    /**
     * 根据输入重构结果生成受治理信号，统一补齐缺省值和降级标记。
     */
    public static GovernedSignal fromReconstruction(String rawInput, InputReconstructionResult reconstruction) {
        boolean debug = containsDebugFlag(rawInput);
        /**
         * 重构结果缺失时返回最小可用信号，提示后续流程需要重新重构。
         */
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
        /**
         * 提炼意图、目标、时间范围和缺失槽位，并判断是否需要触发重构兜底。
         */
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

    /**
     * 仅根据原始输入生成兜底治理信号。
     */
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

    /**
     * 返回去空去重后的约束集合。
     */
    public List<String> safeConstraints() {
        return normalizeList(constraints);
    }

    /**
     * 返回去空去重后的缺失槽位集合。
     */
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
