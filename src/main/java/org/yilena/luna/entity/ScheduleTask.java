package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.TaskStatus;
import org.yilena.luna.enums.TaskType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 日程与待办事项表
 * 用于 Luna 主动提醒或执行任务
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("schedule_task")
public class ScheduleTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 任务内容
     */
    @TableField("content")
    private String content;

    /**
     * 触发时间 (如果是提醒类任务)
     */
    @TableField("trigger_time")
    private LocalDateTime triggerTime;

    /**
     * 状态: 0-待处理, 1-已完成, 2-已取消, 3-已过期
     */
    @TableField("status")
    private TaskStatus status;

    /**
     * 任务类型: REMINDER(提醒), ACTION(执行操作), TODO(待办)
     */
    @TableField("task_type")
    private TaskType taskType;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记 (0: 未删除, 1: 已删除)
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
