package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 日程與待辦事項表
 * 用於 Luna 主動提醒或執行任務
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("schedule_task")
public class ScheduleTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 任務內容
     */
    @TableField("content")
    private String content;

    /**
     * 觸發時間 (如果是提醒類任務)
     */
    @TableField("trigger_time")
    private LocalDateTime triggerTime;

    /**
     * 狀態: 0-待處理, 1-已完成, 2-已取消, 3-已過期
     */
    @TableField("status")
    private Integer status;

    /**
     * 任務類型: REMINDER(提醒), ACTION(執行操作), TODO(待辦)
     */
    @TableField("task_type")
    private String taskType;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
