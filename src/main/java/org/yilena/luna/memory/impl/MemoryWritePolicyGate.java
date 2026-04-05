package org.yilena.luna.memory.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Locale;
import java.util.Map;

@Component
public class MemoryWritePolicyGate {

    @Value("${memory.write-policy.long-term-min-confidence:0.68}")
    private double longTermMinConfidence;

    public GateContext buildContext(String sessionId, StructuredContextPackage contextPackage) {
        TaskRuntimeState taskState = contextPackage == null ? null : contextPackage.getTaskState();
        Map<String, Object> stateSnapshot = contextPackage == null || contextPackage.getContextState() == null
                ? Map.of()
                : safeMap(contextPackage.getContextState().getLatestStateSnapshot());
        double summaryConfidence = readConfidence(stateSnapshot, "summaryConfidence", "snapshotConfidence", "qualityScore");
        double toolConfidence = readConfidence(stateSnapshot, "toolSemanticConfidence", "toolConfidence");
        return new GateContext(
                sessionId == null ? "" : sessionId,
                taskState == null ? TaskRuntimeState.UNDERSTANDING : taskState,
                summaryConfidence,
                toolConfidence
        );
    }

    public GateDecision evaluateLongTermWrite(GateContext context,
                                              String targetType,
                                              String sourceType,
                                              double confidenceHint) {
        return evaluateLongTermWrite(context, targetType, sourceType, confidenceHint, "");
    }

    public GateDecision evaluateLongTermWrite(GateContext context,
                                              String targetType,
                                              String sourceType,
                                              double confidenceHint,
                                              String contentHint) {
        if (context == null) {
            return GateDecision.reject("GATE_CONTEXT_MISSING", 0.0);
        }
        if (isHardDeniedSource(sourceType) || isHardDeniedContent(contentHint)) {
            return GateDecision.reject("HARD_DENY_INTERMEDIATE_OR_PENDING", effectiveConfidence(context, confidenceHint));
        }
        if (isWaitingOrRetryState(context.taskState())) {
            return GateDecision.reject("TASK_STATE_SHORT_TERM_ONLY", effectiveConfidence(context, confidenceHint));
        }
        if (!isReusableSource(sourceType)) {
            return GateDecision.reject("SOURCE_NOT_REUSABLE", effectiveConfidence(context, confidenceHint));
        }
        double confidence = effectiveConfidence(context, confidenceHint);
        double threshold = thresholdForTarget(targetType);
        if (confidence < threshold) {
            return GateDecision.reject("LOW_CONFIDENCE", confidence);
        }
        return GateDecision.allow(confidence);
    }

    public boolean shouldWriteOnlyShortTerm(TaskRuntimeState state) {
        return isWaitingOrRetryState(state == null ? TaskRuntimeState.UNDERSTANDING : state);
    }

    private boolean isWaitingOrRetryState(TaskRuntimeState state) {
        if (state == null) {
            return false;
        }
        return state == TaskRuntimeState.WAITING_USER
                || state == TaskRuntimeState.WAITING_TOOL
                || state == TaskRuntimeState.WAITING_APPROVAL
                || state == TaskRuntimeState.REPLANNING
                || state == TaskRuntimeState.REFLECTING;
    }

    private double thresholdForTarget(String targetType) {
        String normalized = targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        if ("EPISODE".equals(normalized)) {
            return Math.max(0.55, longTermMinConfidence - 0.08);
        }
        if ("PROCEDURE".equals(normalized)) {
            return Math.max(0.60, longTermMinConfidence - 0.04);
        }
        return longTermMinConfidence;
    }

    private boolean isReusableSource(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        return "CONTEXT_REINFORCED".equals(normalized)
                || "SOCIAL_DRAFT".equals(normalized)
                || "SUMMARY_SNAPSHOT".equals(normalized)
                || "TOOL_SEMANTIC".equals(normalized)
                || "USER_INPUT".equals(normalized);
    }

    private boolean isHardDeniedSource(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        return "PENDING_TOOL_RESULT".equals(normalized)
                || "INTERMEDIATE_INFERENCE".equals(normalized)
                || "UNVERIFIED_CONCLUSION".equals(normalized)
                || "RUNTIME_BUFFER".equals(normalized);
    }

    private boolean isHardDeniedContent(String contentHint) {
        String normalized = contentHint == null ? "" : contentHint.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("pending")
                || normalized.contains("intermediate")
                || normalized.contains("unverified")
                || normalized.contains("to be confirmed")
                || normalized.contains("temporary")
                || normalized.contains("未确认")
                || normalized.contains("中间结论")
                || normalized.contains("待工具结果");
    }

    private double effectiveConfidence(GateContext context, double confidenceHint) {
        double hint = bounded(confidenceHint);
        double summary = bounded(context.summaryConfidence());
        double tool = bounded(context.toolSemanticConfidence());
        return Math.max(hint, Math.max(summary, tool));
    }

    private double readConfidence(Map<String, Object> snapshot, String... keys) {
        if (snapshot == null || snapshot.isEmpty() || keys == null) {
            return 0.0;
        }
        for (String key : keys) {
            Object raw = snapshot.get(key);
            if (raw instanceof Number number) {
                return bounded(number.doubleValue());
            }
            if (raw == null) {
                continue;
            }
            try {
                return bounded(Double.parseDouble(String.valueOf(raw).trim()));
            } catch (Exception ignore) {
            }
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private double bounded(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(value, 1.0));
    }

    public record GateContext(String sessionId,
                              TaskRuntimeState taskState,
                              double summaryConfidence,
                              double toolSemanticConfidence) {
    }

    public record GateDecision(boolean allow, String reasonCode, double confidence) {
        private static GateDecision allow(double confidence) {
            return new GateDecision(true, "ALLOW", confidence);
        }

        private static GateDecision reject(String reasonCode, double confidence) {
            return new GateDecision(false, reasonCode, confidence);
        }
    }
}
