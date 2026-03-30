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
public class PlanEventTools extends BaseTool {

    private final PlanEventLogMapper planEventLogMapper; // 声明成员字段
    private final SseSessionManager sseSessionManager; // 声明成员字段

    public PlanEventTools( // 定义方法签名
            ObjectMapper objectMapper, // 执行当前逻辑
            PlanEventLogMapper planEventLogMapper, // 执行当前逻辑
            SseSessionManager sseSessionManager // 执行当前逻辑
    ) { // 开始新的代码块
        super(objectMapper); // 执行语句逻辑
        this.planEventLogMapper = planEventLogMapper; // 执行赋值操作
        this.sseSessionManager = sseSessionManager; // 执行赋值操作
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "记录计划审计日志") // 声明注解
    public String recordPlanAuditLog( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam(value = "phaseId", required = false) String phaseId, // 声明注解
            @RequestParam(value = "nodeId", required = false) String nodeId, // 声明注解
            @RequestParam("level") String level, // 声明注解
            @RequestParam("eventType") String eventType, // 声明注解
            @RequestParam("eventPayload") String eventPayload, // 声明注解
            @RequestParam(value = "traceId", required = false) String traceId // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            PlanEventLog logEntity = PlanEventLog.builder() // 执行赋值操作
                    .planId(planId) // 执行当前逻辑
                    .phaseId(normalizeNullableId(phaseId)) // 执行当前逻辑
                    .nodeId(normalizeNullableId(nodeId)) // 执行当前逻辑
                    .level(parseEventLevel(level)) // 执行当前逻辑
                    .eventType(parseEventType(eventType)) // 执行当前逻辑
                    .eventPayload(objectMapper.readValue(eventPayload, new TypeReference<>() {})) // 执行当前逻辑
                    .traceId(traceId) // 执行当前逻辑
                    .build(); // 执行语句逻辑
            planEventLogMapper.insert(logEntity); // 执行语句逻辑
            log.info("record_plan_audit_log 完成, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}, eventId={}", // 执行赋值操作
                    planId, phaseId, nodeId, eventType, level, traceId, logEntity.getEventId()); // 执行语句逻辑
            return success(Map.of("eventId", logEntity.getEventId())); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("record_plan_audit_log 失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}", // 执行赋值操作
                    planId, phaseId, nodeId, eventType, level, traceId, e); // 执行语句逻辑
            return error("record_plan_audit_log 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "发送计划SSE事件") // 声明注解
    public String emitPlanEventSse( // 定义方法签名
            @RequestParam(value = "clientId", required = false) String clientId, // 声明注解
            @RequestParam("eventType") String eventType, // 声明注解
            @RequestParam("payload") String payload // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            String cid = (clientId == null || clientId.isBlank()) ? "default" : clientId; // 执行赋值操作
            Object body = objectMapper.readValue(payload, Object.class); // 执行赋值操作
            boolean sent = sseSessionManager.send(cid, eventType, body); // 执行赋值操作
            log.info("emit_plan_event_sse 完成, clientId={}, eventType={}, sent={}", cid, eventType, sent); // 执行赋值操作
            return success(Map.of("sent", sent, "clientId", cid, "eventType", eventType)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("emit_plan_event_sse 失败, clientId={}, eventType={}", clientId, eventType, e); // 执行赋值操作
            return error("emit_plan_event_sse 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "发送并落库计划事件") // 声明注解
    public String emitPlanEvent( // 定义方法签名
            @RequestParam(value = "clientId", required = false) String clientId, // 声明注解
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam(value = "phaseId", required = false) String phaseId, // 声明注解
            @RequestParam(value = "nodeId", required = false) String nodeId, // 声明注解
            @RequestParam("level") String level, // 声明注解
            @RequestParam("eventType") String eventType, // 声明注解
            @RequestParam("payload") String payload, // 声明注解
            @RequestParam(value = "traceId", required = false) String traceId // 声明注解
    ) { // 开始新的代码块
        try { // 尝试执行核心逻辑
            String safePhaseId = normalizeNullableId(phaseId); // 执行赋值操作
            String safeNodeId = normalizeNullableId(nodeId); // 执行赋值操作

            String record = recordPlanAuditLog(planId, safePhaseId, safeNodeId, level, eventType, payload, traceId); // 执行赋值操作
            String sse = emitPlanEventSse(clientId, eventType, payload); // 执行赋值操作

            boolean recordError = isError(record); // 执行赋值操作
            boolean sseError = isError(sse); // 执行赋值操作

            Map<String, Object> out = new LinkedHashMap<>(); // 执行赋值操作
            out.put("record", safeReadObject(record)); // 执行语句逻辑
            out.put("sse", safeReadObject(sse)); // 执行语句逻辑
            out.put("recordOk", !recordError); // 执行语句逻辑
            out.put("sseOk", !sseError); // 执行语句逻辑

            if (!recordError && !sseError) { // 进行条件判断
                log.info("emit_plan_event 全成功, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}", // 执行赋值操作
                        planId, safePhaseId, safeNodeId, eventType, level, traceId); // 执行语句逻辑
                return success(out); // 返回处理结果
            } // 结束当前代码块

            if (recordError && !sseError) { // 进行条件判断
                log.warn("emit_plan_event 部分成功：DB失败但SSE成功, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}", // 执行赋值操作
                        planId, safePhaseId, safeNodeId, eventType, level, traceId); // 执行语句逻辑
                return success(out); // 返回处理结果
            } // 结束当前代码块

            if (!recordError) { // 进行条件判断
                log.warn("emit_plan_event 部分成功：DB成功但SSE失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}", // 执行赋值操作
                        planId, safePhaseId, safeNodeId, eventType, level, traceId); // 执行语句逻辑
                return error("emit_plan_event 部分失败: SSE发送失败"); // 返回处理结果
            } // 结束当前代码块

            log.error("emit_plan_event 全失败：DB落库和SSE发送均失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}", // 执行赋值操作
                    planId, safePhaseId, safeNodeId, eventType, level, traceId); // 执行语句逻辑
            return error("emit_plan_event 失败: DB落库和SSE发送均失败"); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("emit_plan_event 失败, planId={}, phaseId={}, nodeId={}, eventType={}, level={}, traceId={}", // 执行赋值操作
                    planId, phaseId, nodeId, eventType, level, traceId, e); // 执行语句逻辑
            return error("emit_plan_event 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private PlanEventLevel parseEventLevel(String level) { // 定义方法签名
        if (level == null || level.isBlank()) { // 进行条件判断
            return PlanEventLevel.INFO; // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            return PlanEventLevel.valueOf(level.trim().toUpperCase()); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.warn("未知事件级别，降级为 INFO, level={}", level); // 执行赋值操作
            return PlanEventLevel.INFO; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private PlanEventType parseEventType(String eventType) { // 定义方法签名
        if (eventType == null || eventType.isBlank()) { // 进行条件判断
            return PlanEventType.PLAN_REPORT_READY; // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            return PlanEventType.valueOf(eventType.trim().toUpperCase()); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.warn("未知事件类型，降级为 PLAN_REPORT_READY, eventType={}", eventType); // 执行赋值操作
            return PlanEventType.PLAN_REPORT_READY; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private boolean isError(String json) { // 定义方法签名
        try { // 尝试执行核心逻辑
            Object obj = objectMapper.readValue(json, Object.class); // 执行赋值操作
            if (obj instanceof Map<?, ?> map) { // 进行条件判断
                Object status = map.get("status"); // 执行赋值操作
                return status != null && "error".equalsIgnoreCase(String.valueOf(status)); // 返回处理结果
            } // 结束当前代码块
            return false; // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return false; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private Object safeReadObject(String json) { // 定义方法签名
        try { // 尝试执行核心逻辑
            return objectMapper.readValue(json, Object.class); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return json; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String normalizeNullableId(String value) { // 定义方法签名
        if (value == null) { // 进行条件判断
            return null; // 返回处理结果
        } // 结束当前代码块
        String v = value.trim(); // 执行赋值操作
        return v.isEmpty() ? null : v; // 返回处理结果
    } // 结束当前代码块
} // 结束当前代码块
