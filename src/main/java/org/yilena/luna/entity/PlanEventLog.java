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
public class PlanEventLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "event_id", type = IdType.ASSIGN_ID)
    private Long eventId;

    @TableField("plan_id")
    private String planId;

    @TableField("phase_id")
    private String phaseId;

    @TableField("node_id")
    private String nodeId;

    @TableField("event_type")
    private PlanEventType eventType;

    @TableField(value = "event_payload", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> eventPayload;

    @TableField("trace_id")
    private String traceId;

    @TableField("level")
    private PlanEventLevel level;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
