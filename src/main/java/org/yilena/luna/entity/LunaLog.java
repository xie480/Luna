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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "luna_log", autoResultMap = true)
public class LunaLog implements Serializable {
    
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("log_type")
    private LogType logType;

    @TableField("module")
    private String module;

    @TableField("action")
    private String action;

    @TableField("content")
    private String content;

    @TableField(value = "request_data", typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> requestData;

    @TableField(value = "response_data", typeHandler = JsonbTypeHandler.class)
    private Object responseData;

    @TableField("error_message")
    private String errorMessage;

    @TableField("error_stack")
    private String errorStack;

    @TableField("cost_time")
    private Long costTime;

    @TableField("operator_id")
    private String operatorId;

    @TableField("trace_id")
    private String traceId;

    @TableField(value = "create_at", fill = FieldFill.INSERT)
    private LocalDateTime createAt;
}
