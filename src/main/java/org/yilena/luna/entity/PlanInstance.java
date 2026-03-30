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
 * PlanInstance ??
 */
public class PlanInstance implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "plan_id", type = IdType.INPUT) // 声明注解
    private String planId; // 声明成员字段

    @TableField("session_id") // 声明注解
    private String sessionId; // 声明成员字段

    @TableField("user_goal") // 声明注解
    private String userGoal; // 声明成员字段

    @TableField(value = "constraints_json", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> constraintsJson; // 声明成员字段

    @TableField("success_criteria") // 声明注解
    private String successCriteria; // 声明成员字段

    @TableField("planning_model") // 声明注解
    private String planningModel; // 声明成员字段

    @TableField("plan_version") // 声明注解
    private Integer planVersion; // 声明成员字段

    @TableField("status") // 声明注解
    private PlanStatus status; // 声明成员字段

    @TableField("current_loop_index") // 声明注解
    private Integer currentLoopIndex; // 声明成员字段

    @TableField("final_status") // 声明注解
    private PlanFinalStatus finalStatus; // 声明成员字段

    @TableField("error_message") // 声明注解
    private String errorMessage; // 声明成员字段

    @TableField("started_at") // 声明注解
    private LocalDateTime startedAt; // 声明成员字段

    @TableField("finished_at") // 声明注解
    private LocalDateTime finishedAt; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE) // 声明注解
    private LocalDateTime updatedAt; // 声明成员字段
} // 结束当前代码块
