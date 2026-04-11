package org.yilena.luna.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 状态消息体，负责统一描述前端状态流中的事件类型、状态码和展示文案。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LunaStatusMessage {

    /**
     * 事件类型，如 luna-status、SKILL_ASYNC_RESULT、PLAN_NODE_SUCCESS。
     */
    private String eventType;

    /**
     * 当前状态码，如 THINKING、SEARCHING、FIXING、IDLE。
     */
    private String status;

    /**
     * 展示给用户的状态文本。
     */
    private String message;

    /**
     * 事件追踪标识，可为空。
     */
    private String traceId;

    /**
     * 任务标识，可为空。
     */
    private String taskId;

    /**
     * 消息发送时间戳。
     */
    private long timestamp;
}
