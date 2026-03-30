package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_checkpoint", autoResultMap = true)
/**
 * PlanCheckpoint ??
 */
public class PlanCheckpoint implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "checkpoint_id", type = IdType.INPUT)
    private String checkpointId;

    @TableField("plan_id")
    private String planId;

    @TableField("phase_id")
    private String phaseId;

    @TableField("node_id")
    private String nodeId;

    @TableField(value = "checkpoint_data", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> checkpointData;

    @TableField("snapshot_hash")
    private String snapshotHash;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
