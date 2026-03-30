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

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "checkpoint_id", type = IdType.INPUT) // 声明注解
    private String checkpointId; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("phase_id") // 声明注解
    private String phaseId; // 声明成员字段

    @TableField("node_id") // 声明注解
    private String nodeId; // 声明成员字段

    @TableField(value = "checkpoint_data", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> checkpointData; // 声明成员字段

    @TableField("snapshot_hash") // 声明注解
    private String snapshotHash; // 声明成员字段

    @TableField("created_by") // 声明注解
    private String createdBy; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段
} // 结束当前代码块
