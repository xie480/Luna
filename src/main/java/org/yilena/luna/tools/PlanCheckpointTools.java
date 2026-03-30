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
import org.yilena.luna.entity.PlanCheckpoint;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.mapper.PlanCheckpointMapper;
import org.yilena.luna.utils.SnowflakeIdUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 计划检查点工具：
 * - checkpoint_plan_state
 */
@Slf4j
@Component
public class PlanCheckpointTools extends BaseTool {

    private final PlanCheckpointMapper planCheckpointMapper; // 声明成员字段
    private final PlanEventTools planEventTools; // 声明成员字段

    public PlanCheckpointTools( // 定义方法签名
            ObjectMapper objectMapper, // 执行当前逻辑
            PlanCheckpointMapper planCheckpointMapper, // 执行当前逻辑
            PlanEventTools planEventTools // 执行当前逻辑
    ) { // 开始新的代码块
        super(objectMapper); // 执行语句逻辑
        this.planCheckpointMapper = planCheckpointMapper; // 执行赋值操作
        this.planEventTools = planEventTools; // 执行赋值操作
    } // 结束当前代码块

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN) // 声明注解
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "创建计划检查点") // 声明注解
    public String checkpointPlanState( // 定义方法签名
            @RequestParam("planId") String planId, // 声明注解
            @RequestParam(value = "phaseId", required = false) String phaseId, // 声明注解
            @RequestParam(value = "nodeId", required = false) String nodeId, // 声明注解
            @RequestParam("checkpointData") String checkpointData, // 声明注解
            @RequestParam(value = "createdBy", required = false) String createdBy // 声明注解
    ) { // 开始新的代码块
        String traceId = UUID.randomUUID().toString(); // 执行赋值操作
        try { // 尝试执行核心逻辑
            if (planId == null || planId.isBlank()) { // 进行条件判断
                return error("planId 不能为空"); // 返回处理结果
            } // 结束当前代码块
            if (checkpointData == null || checkpointData.isBlank()) { // 进行条件判断
                return error("checkpointData 不能为空"); // 返回处理结果
            } // 结束当前代码块

            String checkpointId = SnowflakeIdUtil.nextIdStr(); // 执行赋值操作
            String hash = sha256(checkpointData); // 执行赋值操作
            PlanCheckpoint cp = PlanCheckpoint.builder() // 执行赋值操作
                    .checkpointId(checkpointId) // 执行当前逻辑
                    .planId(planId) // 执行当前逻辑
                    .phaseId(normalizeNullableId(phaseId)) // 执行当前逻辑
                    .nodeId(normalizeNullableId(nodeId)) // 执行当前逻辑
                    .checkpointData(objectMapper.readValue(checkpointData, new TypeReference<>() {})) // 执行当前逻辑
                    .snapshotHash(hash) // 执行当前逻辑
                    .createdBy(createdBy) // 执行当前逻辑
                    .build(); // 执行语句逻辑
            planCheckpointMapper.insert(cp); // 执行语句逻辑

            Map<String, Object> payload = new LinkedHashMap<>(); // 执行赋值操作
            payload.put("planId", planId); // 执行语句逻辑
            payload.put("phaseId", normalizeNullableId(phaseId)); // 执行语句逻辑
            payload.put("nodeId", normalizeNullableId(nodeId)); // 执行语句逻辑
            payload.put("checkpointId", checkpointId); // 执行语句逻辑
            payload.put("snapshotHash", hash); // 执行语句逻辑
            payload.put("createdBy", createdBy == null ? "" : createdBy); // 执行赋值操作

            try { // 尝试执行核心逻辑
                String p = objectMapper.writeValueAsString(payload); // 执行赋值操作
                planEventTools.recordPlanAuditLog( // 执行当前逻辑
                        planId, // 执行当前逻辑
                        normalizeNullableId(phaseId), // 执行当前逻辑
                        normalizeNullableId(nodeId), // 执行当前逻辑
                        "INFO", // 执行当前逻辑
                        "PLAN_CHECKPOINT_CREATED", // 执行当前逻辑
                        p, // 执行当前逻辑
                        traceId // 执行当前逻辑
                ); // 执行语句逻辑
                planEventTools.emitPlanEventSse("default", "PLAN_CHECKPOINT_CREATED", p); // 执行语句逻辑
            } catch (Exception e) { // 开始新的代码块
                log.warn("checkpoint_plan_state 审计/SSE发送失败（不中断） planId={}, checkpointId={}, err={}", planId, checkpointId, e.getMessage()); // 执行赋值操作
            } // 结束当前代码块

            log.info("checkpoint_plan_state 完成, planId={}, checkpointId={}", planId, checkpointId); // 执行赋值操作
            return success(Map.of("checkpointId", checkpointId, "snapshotHash", hash)); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            log.error("checkpoint_plan_state 失败", e); // 执行语句逻辑
            return error("checkpoint_plan_state 失败: " + e.getMessage()); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String normalizeNullableId(String val) { // 定义方法签名
        if (val == null) return null; // 进行条件判断
        String v = val.trim(); // 执行赋值操作
        return v.isEmpty() ? null : v; // 返回处理结果
    } // 结束当前代码块

    private static String sha256(String text) { // 定义方法签名
        try { // 尝试执行核心逻辑
            MessageDigest md = MessageDigest.getInstance("SHA-256"); // 执行赋值操作
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8)); // 执行赋值操作
            StringBuilder sb = new StringBuilder(); // 执行赋值操作
            for (byte b : digest) sb.append(String.format("%02x", b)); // 执行循环处理
            return sb.toString(); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return ""; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
