package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.PlanPhaseStatus;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_phase", autoResultMap = true)
/**
 * PlanPhase ??
 */
public class PlanPhase implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "phase_id", type = IdType.INPUT) // 声明注解
    private String phaseId; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("phase_order") // 声明注解
    private Integer phaseOrder; // 声明成员字段

    @TableField("name") // 声明注解
    private String name; // 声明成员字段

    @TableField("objective") // 声明注解
    private String objective; // 声明成员字段

    @TableField(value = "node_ids", typeHandler = JsonbTypeHandler.class) // 声明注解
    private List<String> nodeIds; // 声明成员字段

    @TableField("entry_criteria") // 声明注解
    private String entryCriteria; // 声明成员字段

    @TableField("exit_criteria") // 声明注解
    private String exitCriteria; // 声明成员字段

    @TableField("status") // 声明注解
    private PlanPhaseStatus status; // 声明成员字段

    @TableField("started_at") // 声明注解
    private LocalDateTime startedAt; // 声明成员字段

    @TableField("finished_at") // 声明注解
    private LocalDateTime finishedAt; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
