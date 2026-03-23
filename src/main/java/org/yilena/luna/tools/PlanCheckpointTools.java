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
import java.util.Map;

/**
 * 计划检查点工具：
 * - checkpoint_plan_state
 */
@Slf4j
@Component
public class PlanCheckpointTools extends BaseTool {

    private final PlanCheckpointMapper planCheckpointMapper;

    public PlanCheckpointTools(ObjectMapper objectMapper, PlanCheckpointMapper planCheckpointMapper) {
        super(objectMapper);
        this.planCheckpointMapper = planCheckpointMapper;
    }

    @LunaState(value = LunaStateConstant.VALUE_PLAN, status = LunaStateConstant.STATUS_PLAN)
    @LunaLogRecord(module = LogModuleConstant.TOOL, action = LogActionConstant.MANAGE_LOG, type = LogType.TOOL_CALL, content = "创建计划检查点")
    public String checkpointPlanState(
            @RequestParam("planId") String planId,
            @RequestParam(value = "phaseId", required = false) String phaseId,
            @RequestParam(value = "nodeId", required = false) String nodeId,
            @RequestParam("checkpointData") String checkpointData,
            @RequestParam(value = "createdBy", required = false) String createdBy
    ) {
        try {
            String checkpointId = SnowflakeIdUtil.nextIdStr();
            String hash = sha256(checkpointData);
            PlanCheckpoint cp = PlanCheckpoint.builder()
                    .checkpointId(checkpointId)
                    .planId(planId)
                    .phaseId(phaseId)
                    .nodeId(nodeId)
                    .checkpointData(objectMapper.readValue(checkpointData, new TypeReference<>() {}))
                    .snapshotHash(hash)
                    .createdBy(createdBy)
                    .build();
            planCheckpointMapper.insert(cp);
            log.info("checkpoint_plan_state 完成, planId={}, checkpointId={}", planId, checkpointId);
            return success(Map.of("checkpointId", checkpointId, "snapshotHash", hash));
        } catch (Exception e) {
            log.error("checkpoint_plan_state 失败", e);
            return error("checkpoint_plan_state 失败: " + e.getMessage());
        }
    }

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
