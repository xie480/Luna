package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.PlanFinalStatus;
import org.yilena.luna.enums.PlanStatus;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_instance", autoResultMap = true)
/**
 * 计划实例实体，负责保存一次计划编排任务的整体运行状态和元数据。
 */
public class PlanInstance implements Serializable {

    /**
     * 序列化版本号，用于计划实例对象持久化与传输兼容。
     */
    private static final long serialVersionUID = 1L;

    @TableId(value = "plan_id", type = IdType.INPUT)
    /**
     * 计划实例唯一标识。
     */
    private String planId;

    @TableField("session_id")
    /**
     * 计划所属会话 ID，用于关联用户上下文。
     */
    private String sessionId;

    @TableField("user_goal")
    /**
     * 用户提出的原始目标描述。
     */
    private String userGoal;

    @TableField(value = "constraints_json", typeHandler = JsonbTypeHandler.class)
    /**
     * 计划执行约束条件的 JSON 描述，例如时间、资源或审批限制。
     */
    private Map<String, Object> constraintsJson;

    @TableField("success_criteria")
    /**
     * 判定计划完成的成功标准描述。
     */
    private String successCriteria;

    @TableField("planning_model")
    /**
     * 负责编排该计划的模型名称。
     */
    private String planningModel;

    @TableField("plan_version")
    /**
     * 当前计划版本号，用于追踪计划重生成或调整次数。
     */
    private Integer planVersion;

    @TableField("status")
    /**
     * 计划当前运行状态。
     */
    private PlanStatus status;

    @TableField("current_loop_index")
    /**
     * 当前执行轮次索引，用于记录循环推进到第几轮。
     */
    private Integer currentLoopIndex;

    @TableField("final_status")
    /**
     * 计划最终归档状态，用于报告和结果展示。
     */
    private PlanFinalStatus finalStatus;

    @TableField("error_message")
    /**
     * 计划失败或异常时记录的错误信息摘要。
     */
    private String errorMessage;

    @TableField("started_at")
    /**
     * 计划开始执行时间。
     */
    private LocalDateTime startedAt;

    @TableField("finished_at")
    /**
     * 计划结束时间。
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
