package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 计划检查点实体，用于保存计划执行过程中的快照数据，支撑恢复、重试和审计分析。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_checkpoint", autoResultMap = true)
public class PlanCheckpoint implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 检查点唯一标识。
     */
    @TableId(value = "checkpoint_id", type = IdType.INPUT)
    private String checkpointId;

    /**
     * 所属计划 ID。
     */
    @TableField("plan_id")
    private String planId;

    /**
     * 所属阶段 ID。
     */
    @TableField("phase_id")
    private String phaseId;

    /**
     * 所属节点 ID。
     */
    @TableField("node_id")
    private String nodeId;

    /**
     * 检查点快照数据，采用 JSONB 保存执行现场。
     */
    @TableField(value = "checkpoint_data", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> checkpointData;

    /**
     * 快照哈希值，用于校验检查点内容是否一致。
     */
    @TableField("snapshot_hash")
    private String snapshotHash;

    /**
     * 检查点创建人标识。
     */
    @TableField("created_by")
    private String createdBy;

    /**
     * 检查点创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
