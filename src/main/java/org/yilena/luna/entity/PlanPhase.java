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
 * 计划阶段实体，负责描述计划中的阶段划分、目标和阶段状态。
 */
public class PlanPhase implements Serializable {

    /**
     * 序列化版本号，用于阶段对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @TableId(value = "phase_id", type = IdType.INPUT)
    /**
     * 阶段唯一标识。
     */
    private String phaseId;

    @TableField("plan_id")
    /**
     * 阶段所属计划 ID。
     */
    private String planId;

    @TableField("phase_order")
    /**
     * 阶段执行顺序，从小到大表示先后关系。
     */
    private Integer phaseOrder;

    @TableField("name")
    /**
     * 阶段名称。
     */
    private String name;

    @TableField("objective")
    /**
     * 当前阶段要完成的业务目标描述。
     */
    private String objective;

    @TableField(value = "node_ids", typeHandler = JsonbTypeHandler.class)
    /**
     * 当前阶段包含的节点 ID 列表。
     */
    private List<String> nodeIds;

    @TableField("entry_criteria")
    /**
     * 阶段进入条件，用于判断是否满足执行前提。
     */
    private String entryCriteria;

    @TableField("exit_criteria")
    /**
     * 阶段退出条件，用于判断阶段是否完成。
     */
    private String exitCriteria;

    @TableField("status")
    /**
     * 当前阶段执行状态。
     */
    private PlanPhaseStatus status;

    @TableField("started_at")
    /**
     * 阶段开始执行时间。
     */
    private LocalDateTime startedAt;

    @TableField("finished_at")
    /**
     * 阶段结束执行时间。
     */
    private LocalDateTime finishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    /**
     * 记录创建时间。
     */
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    /**
     * 记录最后更新时间。
     */
    private LocalDateTime updatedAt;
}
