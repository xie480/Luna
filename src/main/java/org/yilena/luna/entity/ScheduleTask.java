package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.TaskStatus;
import org.yilena.luna.enums.TaskType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 日程任务实体，用于保存提醒、待办和动作执行任务，支撑 Luna 的主动触发能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("schedule_task")
public class ScheduleTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务主键 ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 任务内容，用于描述需要提醒或执行的事项。
     */
    @TableField("content")
    private String content;

    /**
     * 任务触发时间，用于控制提醒或执行时机。
     */
    @TableField("trigger_time")
    private LocalDateTime triggerTime;

    /**
     * 任务状态，用于区分待处理、已完成、已取消等阶段。
     */
    @TableField("status")
    private TaskStatus status;

    /**
     * 任务类型，用于区分提醒、动作和待办等业务类别。
     */
    @TableField("task_type")
    private TaskType taskType;

    /**
     * 任务创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 任务最后更新时间。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记，0 表示未删除，1 表示已删除。
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
