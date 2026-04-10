package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

/**
 * 计划事件日志实体，用于记录计划执行期间的关键事件、上下文载荷和链路级别信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "plan_event_log", autoResultMap = true)
public class PlanEventLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件主键 ID。
     */
    @TableId(value = "event_id", type = IdType.ASSIGN_ID)
    private Long eventId;

    /**
     * 所属计划 ID。
     */
    @TableField("plan_id")
    private String planId;

    /**
     * 所属阶段 ID。
     */
    @TableField("phase_id")
    private String phaseId;

    /**
     * 所属节点 ID。
     */
    @TableField("node_id")
    private String nodeId;

    /**
     * 事件类型，用于标识阶段启动、节点完成等业务事件。
     */
    @TableField("event_type")
    private PlanEventType eventType;

    /**
     * 事件载荷数据，采用 JSONB 记录补充上下文。
     */
    @TableField(value = "event_payload", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> eventPayload;

    /**
     * 事件所属链路追踪 ID。
     */
    @TableField("trace_id")
    private String traceId;

    /**
     * 事件级别，用于区分普通、警告、错误等严重程度。
     */
    @TableField("level")
    private PlanEventLevel level;

    /**
     * 事件创建时间。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
