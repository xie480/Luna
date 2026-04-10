package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.SummaryTraceLogger;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.RuntimeAuditService;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 轮次摘要追踪日志实现，负责记录摘要生成时的输入上下文和输出结果，
 * 便于审计摘要链路是否丢失关键信息。
 */
public class RuntimeSummaryTraceLogger implements SummaryTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 记录默认摘要追踪日志。
     */
    public void log(String sessionId,
                    Long planId,
                    Long nodeId,
                    String userInput,
                    String assistantReply,
                    StructuredContextPackage contextPackage,
                    SummaryResult summaryResult,
                    String triggerSource) {
        log(sessionId, planId, nodeId, userInput, assistantReply, contextPackage, summaryResult, triggerSource, Map.of());
    }

    @Override
    /**
     * 将摘要输入、触发来源和摘要结果写入运行态审计日志。
     */
    public void log(String sessionId,
                    Long planId,
                    Long nodeId,
                    String userInput,
                    String assistantReply,
                    StructuredContextPackage contextPackage,
                    SummaryResult summaryResult,
                    String triggerSource,
                    Map<String, Object> traceMeta) {
        try {
            /**
             * 固化本次摘要生成的完整上下文输入和结果，
             * 便于对比摘要前后是否出现事实压缩偏差。
             */
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("traceId", traceMeta == null ? "" : String.valueOf(traceMeta.getOrDefault("traceId", "")));
            payload.put("traceLayer", "SUMMARY");
            payload.put("nodeId", nodeId);
            payload.put("snapshotId", contextPackage == null || contextPackage.getContextState() == null
                    ? ""
                    : String.valueOf(contextPackage.getContextState().getLatestContextSnapshotId()));
            payload.put("recoveryEvent", contextPackage == null || contextPackage.getRecoveryState() == null
                    ? ""
                    : String.valueOf(contextPackage.getRecoveryState().getRecoveryEvent()));
            payload.put("triggerSource", triggerSource == null ? "UNKNOWN" : triggerSource);
            payload.put("userInput", userInput == null ? "" : userInput);
            payload.put("assistantReply", assistantReply == null ? "" : assistantReply);
            payload.put("runtimeContextInput", contextPackage == null ? Map.of() : contextPackage);
            payload.put("summaryResult", summaryResult == null ? Map.of() : summaryResult);
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "SUMMARY_TRACE",
                    "summary agent generated dual summaries with full input payload",
                    objectMapper.writeValueAsString(payload)
            );
        } catch (Exception ignore) {
        }
    }
}
