package org.yilena.luna.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.yilena.luna.annotation.LunaLogRecord;
import org.yilena.luna.annotation.LunaState;
import org.yilena.luna.constants.LogActionConstant;
import org.yilena.luna.constants.LogModuleConstant;
import org.yilena.luna.constants.LunaStateConstant;
import org.yilena.luna.entity.PlanEventLog;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.enums.PlanEventLevel;
import org.yilena.luna.enums.PlanEventType;
import org.yilena.luna.mapper.PlanEventLogMapper;
import org.yilena.luna.sse.SseSessionManager;

import java.util.Map;

/**
 * Plan 事件与审计工具：
 * - record_plan_audit_log
 * - emit_plan_event_sse
 * - emit_plan_event（SSE + DB 双写封装）
 */
@Slf4j
@Component
public class PlanEventTools extends BaseTool {

    private final PlanEventLogMapper planEventLogMapper;
    private final SseSessionManager sseSessionManager;

    public PlanEventTools(
            ObjectMapper objectMapper,
            PlanEventLogMapper planEventLogMapper,
            SseSessionManager sseSessionManager
    ) {
        super(objectMapper);
        this.planEventLogMapper = planEventLogMapper;
        this.sseSessionManager = sseSessionManager;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "记录计划审计日志")
    public String recordPlanAuditLog(
            @RequestParam("planId") String planId,
            @RequestParam(value = "phaseId", required = false) String phaseId,
            @RequestParam(value = "nodeId", required = false) String nodeId,
            @RequestParam("level") String level,
            @RequestParam("eventType") String eventType,
            @RequestParam("eventPayload") String eventPayload,
            @RequestParam(value = "traceId", required = false) String traceId
    ) {
        try {
            PlanEventLog logEntity = PlanEventLog.builder()
                    .planId(planId)
                    .phaseId(phaseId)
                    .nodeId(nodeId)
                    .level(PlanEventLevel.valueOf(level.toUpperCase()))
                    .eventType(PlanEventType.valueOf(eventType.toUpperCase()))
                    .eventPayload(objectMapper.readValue(eventPayload, new TypeReference<>() {}))
                    .traceId(traceId)
                    .build();
            planEventLogMapper.insert(logEntity);
            log.info("record_plan_audit_log 完成, planId={}, eventType={}", planId, eventType);
            return success(Map.of("eventId", logEntity.getEventId()));
        } catch (Exception e) {
            log.error("record_plan_audit_log 失败", e);
            return error("record_plan_audit_log 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "发送计划SSE事件")
    public String emitPlanEventSse(
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam("eventType") String eventType,
            @RequestParam("payload") String payload
    ) {
        try {
            String cid = (clientId == null || clientId.isBlank()) ? "default" : clientId;
            Object body = objectMapper.readValue(payload, Object.class);
            boolean sent = sseSessionManager.send(cid, eventType, body);
            log.info("emit_plan_event_sse 完成, clientId={}, eventType={}, sent={}", cid, eventType, sent);
            return success(Map.of("sent", sent, "clientId", cid, "eventType", eventType));
        } catch (Exception e) {
            log.error("emit_plan_event_sse 失败", e);
            return error("emit_plan_event_sse 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "发送并落库计划事件")
    public String emitPlanEvent(
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam("planId") String planId,
            @RequestParam(value = "phaseId", required = false) String phaseId,
            @RequestParam(value = "nodeId", required = false) String nodeId,
            @RequestParam("level") String level,
            @RequestParam("eventType") String eventType,
            @RequestParam("payload") String payload,
            @RequestParam(value = "traceId", required = false) String traceId
    ) {
        try {
            String record = recordPlanAuditLog(planId, phaseId, nodeId, level, eventType, payload, traceId);
            if (isError(record)) {
                return record;
            }
            String sse = emitPlanEventSse(clientId, eventType, payload);
            if (isError(sse)) {
                return sse;
            }
            return success(Map.of("record", objectMapper.readValue(record, Object.class), "sse", objectMapper.readValue(sse, Object.class)));
        } catch (Exception e) {
            log.error("emit_plan_event 失败", e);
            return error("emit_plan_event 失败: " + e.getMessage());
        }
    }

    private boolean isError(String json) {
        try {
            Object obj = objectMapper.readValue(json, Object.class);
            if (obj instanceof Map<?, ?> map) {
                Object status = map.get("status");
                return status != null && "error".equalsIgnoreCase(String.valueOf(status));
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
