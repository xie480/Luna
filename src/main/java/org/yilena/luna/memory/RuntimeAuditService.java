package org.yilena.luna.memory;

import org.yilena.luna.context.model.AssembledContext;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Map;

/**
 * 运行审计服务接口，负责持久化上下文快照、决策记录和工具执行轨迹，
 * 为问题排查、回放分析和状态恢复提供审计依据。
 */
public interface RuntimeAuditService {
    void persistContextSnapshot(String sessionId, StructuredContextPackage contextPackage);

    String persistFinalContextSnapshot(String sessionId,
                                       Long planId,
                                       Long nodeId,
                                       AssembledContext assembledContext,
                                       String prompt,
                                       Map<String, Integer> sectionTokenCounts,
                                       Map<String, Double> sectionTokenRatios,
                                       Map<String, Object> rawToolResultChannel,
                                       Map<String, java.util.List<String>> activeRefs);

    default String persistFinalContextSnapshot(String sessionId,
                                               Long planId,
                                               Long nodeId,
                                               AssembledContext assembledContext,
                                               String prompt,
                                               Map<String, Integer> sectionTokenCounts,
                                               Map<String, Double> sectionTokenRatios,
                                               Map<String, Object> rawToolResultChannel,
                                               Map<String, java.util.List<String>> activeRefs,
                                               Map<String, Object> structuredRecoveryPayload) {
        return persistFinalContextSnapshot(
                sessionId,
                planId,
                nodeId,
                assembledContext,
                prompt,
                sectionTokenCounts,
                sectionTokenRatios,
                rawToolResultChannel,
                activeRefs
        );
    }

    void persistDecisionRecord(String sessionId,
                               Long planId,
                               Long nodeId,
                               String decisionType,
                               String decisionReason,
                               String decisionPayloadJson);

    void persistToolExecutionTrace(String sessionId,
                                   Long planId,
                                   Long nodeId,
                                   String toolName,
                                   String callStatus,
                                   String normalizedInputJson,
                                   String normalizedOutputJson,
                                   String errorMessage,
                                   Long latencyMs);

    default Long persistToolExecutionTraceAndReturnId(String sessionId,
                                                      Long planId,
                                                      Long nodeId,
                                                      String toolName,
                                                      String callStatus,
                                                      String normalizedInputJson,
                                                      String normalizedOutputJson,
                                                      String errorMessage,
                                                      Long latencyMs) {
        persistToolExecutionTrace(
                sessionId,
                planId,
                nodeId,
                toolName,
                callStatus,
                normalizedInputJson,
                normalizedOutputJson,
                errorMessage,
                latencyMs
        );
        return null;
    }
}
