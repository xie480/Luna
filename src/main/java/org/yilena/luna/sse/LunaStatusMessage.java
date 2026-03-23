package org.yilena.luna.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一状态消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LunaStatusMessage {
    /**
     * 事件类型，如 luna-status / SKILL_ASYNC_RESULT / PLAN_NODE_SUCCESS
     */
    private String eventType;

    /**
     * 状态码，如 THINKING, SEARCHING, FIXING, IDLE
     */
    private String status;

    /**
     * 展示给用户的文本
     */
    private String message;

    /**
     * 追踪ID（可选）
     */
    private String traceId;

    /**
     * 任务ID（可选）
     */
    private String taskId;

    /**
     * 时间戳
     */
    private long timestamp;
}
