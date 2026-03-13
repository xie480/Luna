package org.yilena.luna.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 异常上下文对象
 * 用于在异常发生时，向 AI 提供完整的环境信息
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LunaExceptionContext implements Serializable {
    /**
     * 异常类型 (e.g., NullPointerException)
     */
    private String errorType;

    /**
     * 异常详细信息
     */
    private String errorMessage;

    /**
     * 堆栈信息
     */
    private String stackTrace;

    /**
     * 请求 URI
     */
    private String requestUri;

    /**
     * HTTP 方法 (GET, POST...)
     */
    private String requestMethod;

    /**
     * 请求参数
     */
    private Map<String, String[]> requestParams;

    /**
     * 用户原始输入 (如果有)
     */
    private String userInput;

    /**
     * 异常发生时间
     */
    private LocalDateTime timestamp;

    /**
     * 当前重试次数 (防止无限循环)
     */
    private int retryCount;
}
