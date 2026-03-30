package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.PlanEventLevel;
import org.yilena.luna.enums.PlanEventType;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_event_log", autoResultMap = true)
/**
 * PlanEventLog ??
 */
public class PlanEventLog implements Serializable {

    private static final long serialVersionUID = 1L; // 声明成员字段

    @TableId(value = "event_id", type = IdType.ASSIGN_ID) // 声明注解
    private Long eventId; // 声明成员字段

    @TableField("plan_id") // 声明注解
    private String planId; // 声明成员字段

    @TableField("phase_id") // 声明注解
    private String phaseId; // 声明成员字段

    @TableField("node_id") // 声明注解
    private String nodeId; // 声明成员字段

    @TableField("event_type") // 声明注解
    private PlanEventType eventType; // 声明成员字段

    @TableField(value = "event_payload", typeHandler = JsonbTypeHandler.class) // 声明注解
    private Map<String, Object> eventPayload; // 声明成员字段

    @TableField("trace_id") // 声明注解
    private String traceId; // 声明成员字段

    @TableField("level") // 声明注解
    private PlanEventLevel level; // 声明成员字段

    @TableField(value = "created_at", fill = FieldFill.INSERT) // 声明注解
    private LocalDateTime createdAt; // 声明成员字段
} // 结束当前代码块
