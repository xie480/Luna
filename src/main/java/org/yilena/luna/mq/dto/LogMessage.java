package org.yilena.luna.mq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.enums.LogType;

import java.io.Serializable;
import java.util.Map;

/**
 * 日志消息体，负责承载异步日志落库所需的基础信息、请求响应数据和扩展上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogMessage implements Serializable {

    /**
     * 日志类型。
     */
    private LogType logType;

    /**
     * 日志所属模块。
     */
    private String module;

    /**
     * 模块内动作名称。
     */
    private String action;

    /**
     * 日志主体内容。
     */
    private String content;

    /**
     * 请求数据快照。
     */
    private Map<String, Object> requestData;

    /**
     * 响应数据快照。
     */
    private Object responseData;

    /**
     * 错误信息摘要。
     */
    private String errorMessage;

    /**
     * 错误堆栈信息。
     */
    private String errorStack;

    /**
     * 执行耗时，单位为毫秒。
     */
    private Long costTime;

    /**
     * 链路追踪标识。
     */
    private String traceId;

    /**
     * 日志创建时间戳，单位为毫秒。
     */
    private Long createTime;

    /**
     * 会话标识，便于跨模块追踪。
     */
    private String sessionId;

    /**
     * 计划标识。
     */
    private String planId;

    /**
     * 阶段标识。
     */
    private String phaseId;

    /**
     * 节点标识。
     */
    private String nodeId;
}
