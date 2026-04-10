package org.yilena.luna.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
 * 系统日志实体，用于记录对话流程、工具调用、异常信息等运行轨迹，支撑问题排查与审计分析。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "luna_log", autoResultMap = true)
public class LunaLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 日志主键 ID。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 日志类型，用于区分系统事件、工具调用、异常等类别。
     */
    @TableField("log_type")
    private LogType logType;

    /**
     * 所属模块标识，例如聊天、工具、系统等模块。
     */
    @TableField("module")
    private String module;

    /**
     * 当前日志对应的操作名称。
     */
    @TableField("action")
    private String action;

    /**
     * 日志正文内容，用于描述当前事件的业务信息。
     */
    @TableField("content")
    private String content;

    /**
     * 请求侧附加数据，采用 JSONB 结构保存上下文参数。
     */
    @TableField(value = "request_data", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> requestData;

    /**
     * 响应侧附加数据，用于记录执行结果或返回内容。
     */
    @TableField(value = "response_data", typeHandler = JsonbTypeHandler.class)
    private Object responseData;

    /**
     * 异常简要信息，用于快速定位失败原因。
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 异常堆栈详情，用于深度排查错误来源。
     */
    @TableField("error_stack")
    private String errorStack;

    /**
     * 本次操作耗时，单位为毫秒。
     */
    @TableField("cost_time")
    private Long costTime;

    /**
     * 操作人标识，可记录用户 ID 或系统执行主体。
     */
    @TableField("operator_id")
    private String operatorId;

    /**
     * 链路追踪 ID，用于串联同一次请求的上下文。
     */
    @TableField("trace_id")
    private String traceId;

    /**
     * 日志创建时间。
     */
    @TableField(value = "create_at", fill = FieldFill.INSERT)
    private LocalDateTime createAt;
}
