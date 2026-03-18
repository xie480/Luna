package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.LogType;
import org.yilena.luna.handler.JsonbTypeHandler;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统日志实体类
 * 用于记录系统运行过程中的各类事件、工具调用、异常等信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "luna_log", autoResultMap = true)
public class LunaLog implements Serializable {
    
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 日志类型 (如: 系统事件, 工具调用, 异常报错等)
     */
    @TableField("log_type")
    private LogType logType;

    /**
     * 模块名称 (如: CHAT, TOOL, SYSTEM)
     */
    @TableField("module")
    private String module;

    /**
     * 操作名称 (如: SEARCH_WEB, STARTUP)
     */
    @TableField("action")
    private String action;

    /**
     * 日志内容/描述信息
     */
    @TableField("content")
    private String content;

    /**
     * 请求数据 (以 JSONB 格式存储)
     */
    @TableField(value = "request_data", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> requestData;

    /**
     * 响应数据 (以 JSONB 格式存储)
     */
    @TableField(value = "response_data", typeHandler = JsonbTypeHandler.class)
    private Object responseData;

    /**
     * 错误信息 (发生异常时的简短错误提示)
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 错误堆栈 (发生异常时的完整堆栈信息)
     */
    @TableField("error_stack")
    private String errorStack;

    /**
     * 耗时 (单位: 毫秒)
     */
    @TableField("cost_time")
    private Long costTime;

    /**
     * 操作人 ID (如用户 ID 或系统默认 ID)
     */
    @TableField("operator_id")
    private String operatorId;

    /**
     * 链路追踪 ID (用于串联一次完整的请求上下文)
     */
    @TableField("trace_id")
    private String traceId;

    /**
     * 创建时间
     */
    @TableField(value = "create_at", fill = FieldFill.INSERT)
    private LocalDateTime createAt;
}
