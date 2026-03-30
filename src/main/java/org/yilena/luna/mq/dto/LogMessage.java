package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.LogType;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * LogMessage ??
 */
public class LogMessage implements Serializable {
    private LogType logType;
    private String module;
    private String action;
    private String content;
    private Map<String, Object> requestData;
    private Object responseData;
    private String errorMessage;
    private String errorStack;
    private Long costTime;
    private String traceId;
    private Long createTime;

    /**
     * 扩展上下文字段（可选）
     * 用于增强跨模块日志诊断能力
     */
    private String sessionId;
    private String planId;
    private String phaseId;
    private String nodeId;
}
