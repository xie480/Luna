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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plan 事件与审计工具：
 * - record_plan_audit_log
 * - emit_plan_event_sse
 * - emit_plan_event（SSE + DB 双写封装）
 */
@Slf4j
@Component
/**
 * 计划事件工具类，负责计划审计日志落库、SSE 事件推送以及两者的统一编排。
 */
public class PlanEventTools extends BaseTool {

    /**
     * 计划事件日志数据访问对象，用于持久化审计事件。
     */
    private final PlanEventLogMapper planEventLogMapper;
    /**
     * SSE 会话管理器，用于向前端或订阅方推送计划事件。
     */
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
    /**
     * 记录计划审计日志，将事件级别、类型和负载持久化到事件日志表。
     */
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
            /**
             * 先组装事件实体并解析等级、类型与负载结构，再写入审计日志。
             */
            PlanEventLog logEntity = PlanEventLog.builder()
                    .planId(planId)
                    .phaseId(normalizeNullableId(phaseId))
                    .nodeId(normalizeNullableId(nodeId))
                    .level(parseEventLevel(level))
                    .eventType(parseEventType(eventType))
                    .eventPayload(objectMapper.readValue(eventPayload, new TypeReference<>() {}))
                    .traceId(traceId)
                    .build();
            planEventLogMapper.insert(logEntity);
            log.info("record_plan_audit_log 完成, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}, eventId={}",
                    planId, phaseId, nodeId, eventType, level, traceId, logEntity.getEventId());
            return success(Map.of("eventId", logEntity.getEventId()));
        } catch (Exception e) {
            log.error("record_plan_audit_log 失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}",
                    planId, phaseId, nodeId, eventType, level, traceId, e);
            return error("record_plan_audit_log 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "发送计划SSE事件")
    /**
     * 发送计划 SSE 事件，将结构化负载推送给指定客户端或默认订阅方。
     */
    public String emitPlanEventSse(
            @RequestParam(value = "clientId", required = false) String clientId,
            @RequestParam("eventType") String eventType,
            @RequestParam("payload") String payload
    ) {
        try {
            /**
             * 先规范化客户端标识并解析负载 JSON，确保推送事件结构稳定。
             */
            String cid = (clientId == null || clientId.isBlank()) ? "default" : clientId;
            Object body = objectMapper.readValue(payload, Object.class);
            /**
             * 通过 SSE 会话管理器发送事件，返回本次推送是否命中有效订阅者。
             */
            boolean sent = sseSessionManager.send(cid, eventType, body);
            log.info("emit_plan_event_sse 完成, clientId={}, eventType={}, sent={}", cid, eventType, sent);
            return success(Map.of("sent", sent, "clientId", cid, "eventType", eventType));
        } catch (Exception e) {
            log.error("emit_plan_event_sse 失败, clientId={}, eventType={}", clientId, eventType, e);
            return error("emit_plan_event_sse 失败: " + e.getMessage());
        }
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "发送并落库计划事件")
    /**
     * 统一发送计划事件，同时执行审计落库和 SSE 推送，并汇总两条链路的执行结果。
     */
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
            /**
             * 先规范化阶段和节点标识，避免空白字符串污染事件数据。
             */
            String safePhaseId = normalizeNullableId(phaseId);
            String safeNodeId = normalizeNullableId(nodeId);

            /**
             * 审计日志与 SSE 推送分别执行，并在结果汇总时区分完全成功与部分成功场景。
             */
            String record = recordPlanAuditLog(planId, safePhaseId, safeNodeId, level, eventType, payload, traceId);
            String sse = emitPlanEventSse(clientId, eventType, payload);

            boolean recordError = isError(record);
            boolean sseError = isError(sse);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("record", safeReadObject(record));
            out.put("sse", safeReadObject(sse));
            out.put("recordOk", !recordError);
            out.put("sseOk", !sseError);

            if (!recordError && !sseError) {
                log.info("emit_plan_event 全成功, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}",
                        planId, safePhaseId, safeNodeId, eventType, level, traceId);
                return success(out);
            }

            if (recordError && !sseError) {
                log.warn("emit_plan_event 部分成功：DB失败但SSE成功, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}",
                        planId, safePhaseId, safeNodeId, eventType, level, traceId);
                return success(out);
            }

            if (!recordError) {
                log.warn("emit_plan_event 部分成功：DB成功但SSE失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}",
                        planId, safePhaseId, safeNodeId, eventType, level, traceId);
                return error("emit_plan_event 部分失败: SSE发送失败");
            }

            log.error("emit_plan_event 全失败：DB落库和SSE发送均失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}",
                    planId, safePhaseId, safeNodeId, eventType, level, traceId);
            return error("emit_plan_event 失败: DB落库和SSE发送均失败");
        } catch (Exception e) {
            log.error("emit_plan_event 失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}",
                    planId, phaseId, nodeId, eventType, level, traceId, e);
            return error("emit_plan_event 失败: " + e.getMessage());
        }
    }

    /**
     * 解析事件级别，非法值自动降级为 INFO。
     */
    private PlanEventLevel parseEventLevel(String level) {
        if (level == null || level.isBlank()) {
            return PlanEventLevel.INFO;
        }
        try {
            return PlanEventLevel.valueOf(level.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("未知事件级别，降级为 INFO, level={}", level);
            return PlanEventLevel.INFO;
        }
    }

    /**
     * 解析事件类型，非法值自动降级为默认报告完成事件。
     */
    private PlanEventType parseEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return PlanEventType.PLAN_REPORT_READY;
        }
        try {
            return PlanEventType.valueOf(eventType.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("未知事件类型，降级为 PLAN_REPORT_READY, eventType={}", eventType);
            return PlanEventType.PLAN_REPORT_READY;
        }
    }

    /**
     * 判断统一响应 JSON 是否为错误结果，用于汇总双写链路状态。
     */
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

    /**
     * 尝试将响应 JSON 解析为对象，解析失败时保留原始字符串。
     */
    private Object safeReadObject(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 将可选标识规范化为 null 或去空格后的有效值。
     */
    private String normalizeNullableId(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
