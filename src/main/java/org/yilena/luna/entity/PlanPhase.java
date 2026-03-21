package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.PlanPhaseStatus;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_phase", autoResultMap = true)
public class PlanPhase implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "phase_id", type = IdType.INPUT)
    private String phaseId;

    @TableField("plan_id")
    private String planId;

    @TableField("phase_order")
    private Integer phaseOrder;

    @TableField("name")
    private String name;

    @TableField("objective")
    private String objective;

    @TableField(value = "node_ids", typeHandler = JacksonTypeHandler.class)
    private List<String> nodeIds;

    @TableField("entry_criteria")
    private String entryCriteria;

    @TableField("exit_criteria")
    private String exitCriteria;

    @TableField("status")
    private PlanPhaseStatus status;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
