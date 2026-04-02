package org.yilena.luna.context.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.yilena.luna.context.SummaryTraceLogger;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.memory.RuntimeAuditService;

@Service
@RequiredArgsConstructor
public class RuntimeSummaryTraceLogger implements SummaryTraceLogger {

    private final RuntimeAuditService runtimeAuditService;
    private final ObjectMapper objectMapper;

    @Override
    public void log(String sessionId, Long planId, Long nodeId, SummaryResult summaryResult) {
        try {
            runtimeAuditService.persistDecisionRecord(
                    sessionId,
                    planId,
                    nodeId,
                    "SUMMARY_TRACE",
                    "summary agent generated dual summaries",
                    objectMapper.writeValueAsString(summaryResult == null ? java.util.Map.of() : summaryResult)
            );
        } catch (Exception ignore) {
        }
    }
}

