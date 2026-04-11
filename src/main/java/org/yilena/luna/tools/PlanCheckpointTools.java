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
/**
 * 计划检查点工具类，负责对计划执行过程生成快照检查点，便于后续追溯、恢复和审计。
 */
public class PlanCheckpointTools extends BaseTool {

    /**
     * 计划检查点数据访问对象，用于持久化快照记录。
     */
    private final PlanCheckpointMapper planCheckpointMapper;
    /**
     * 计划事件工具，用于在检查点创建后写入审计日志并推送事件。
     */
    private final PlanEventTools planEventTools;

    public PlanCheckpointTools(
            ObjectMapper objectMapper,
            PlanCheckpointMapper planCheckpointMapper,
            PlanEventTools planEventTools
    ) {
        super(objectMapper);
        this.planCheckpointMapper = planCheckpointMapper;
        this.planEventTools = planEventTools;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "创建计划检查点")
    /**
     * 为当前计划、阶段或节点创建状态快照，并同步发布检查点创建事件。
     */
    public String checkpointPlanState(
            @RequestParam("planId") String planId,
            @RequestParam(value = "phaseId", required = false) String phaseId,
            @RequestParam(value = "nodeId", required = false) String nodeId,
            @RequestParam("checkpointData") String checkpointData,
            @RequestParam(value = "createdBy", required = false) String createdBy
    ) {
        String traceId = UUID.randomUUID().toString();
        try {
            /**
             * 先校验计划标识和快照内容，避免生成无效检查点。
             */
            if (planId == null || planId.isBlank()) {
                return error("planId 不能为空");
            }
            if (checkpointData == null || checkpointData.isBlank()) {
                return error("checkpointData 不能为空");
            }

            /**
             * 生成检查点主键和快照摘要，再将快照内容落库，形成可追溯的状态节点。
             */
            String checkpointId = SnowflakeIdUtil.nextIdStr();
            String hash = sha256(checkpointData);
            PlanCheckpoint cp = PlanCheckpoint.builder()
                    .checkpointId(checkpointId)
                    .planId(planId)
                    .phaseId(normalizeNullableId(phaseId))
                    .nodeId(normalizeNullableId(nodeId))
                    .checkpointData(objectMapper.readValue(checkpointData, new TypeReference<>() {}))
                    .snapshotHash(hash)
                    .createdBy(createdBy)
                    .build();
            planCheckpointMapper.insert(cp);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", planId);
            payload.put("phaseId", normalizeNullableId(phaseId));
            payload.put("nodeId", normalizeNullableId(nodeId));
            payload.put("checkpointId", checkpointId);
            payload.put("snapshotHash", hash);
            payload.put("createdBy", createdBy == null ? "" : createdBy);

            try {
                /**
                 * 检查点写入成功后补发审计日志和 SSE 事件，通知外部链路有新快照产生。
                 */
                String p = objectMapper.writeValueAsString(payload);
                planEventTools.recordPlanAuditLog(
                        planId,
                        normalizeNullableId(phaseId),
                        normalizeNullableId(nodeId),
                        "INFO",
                        "PLAN_CHECKPOINT_CREATED",
                        p,
                        traceId
                );
                planEventTools.emitPlanEventSse("default", "PLAN_CHECKPOINT_CREATED", p);
            } catch (Exception e) {
                log.warn("checkpoint_plan_state 审计/SSE发送失败（不中断） planId={}, checkpointId={}, err={}", planId, checkpointId, e.getMessage());
            }

            log.info("checkpoint_plan_state 完成, planId={}, checkpointId={}", planId, checkpointId);
            return success(Map.of("checkpointId", checkpointId, "snapshotHash", hash));
        } catch (Exception e) {
            log.error("checkpoint_plan_state 失败", e);
            return error("checkpoint_plan_state 失败: " + e.getMessage());
        }
    }

    /**
     * 将可选标识规范化为 null 或去空格后的有效值。
     */
    private String normalizeNullableId(String val) {
        if (val == null) return null;
        String v = val.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 计算快照内容的 SHA-256 摘要，用于标识同一份状态快照。
     */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
