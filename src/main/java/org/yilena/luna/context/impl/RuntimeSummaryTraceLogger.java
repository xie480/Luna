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
public class RuntimeSummaryTraceLogger implements SummaryTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String sessionId,
                    Long planId,
                    Long nodeId,
                    String userInput,
                    String assistantReply,
                    StructuredContextPackage contextPackage,
                    SummaryResult summaryResult,
                    String triggerSource) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
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
