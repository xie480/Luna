package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.PlanFinalStatus;
import org.yilena.luna.enums.PlanStatus;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_instance", autoResultMap = true)
public class PlanInstance implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "plan_id", type = IdType.INPUT)
    private String planId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_goal")
    private String userGoal;

    @TableField(value = "constraints_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> constraintsJson;

    @TableField("success_criteria")
    private String successCriteria;

    @TableField("planning_model")
    private String planningModel;

    @TableField("plan_version")
    private Integer planVersion;

    @TableField("status")
    private PlanStatus status;

    @TableField("current_loop_index")
    private Integer currentLoopIndex;

    @TableField("final_status")
    private PlanFinalStatus finalStatus;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
