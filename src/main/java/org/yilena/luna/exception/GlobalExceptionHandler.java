package org.yilena.luna.exception;

import cn.hutool.core.exceptions.ExceptionUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.yilena.luna.service.ExceptionRetryService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 全局异常处理器
 * 捕获系统异常并转交给 AI Agent 进行分析与尝试修复
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ExceptionRetryService exceptionRetryService;

    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleException(Exception e, HttpServletRequest request) {
        log.error("捕获全局异常: {}", e.getMessage(), e);

        // 1. 构建异常上下文
        LunaExceptionContext context = LunaExceptionContext.builder()
                .errorType(e.getClass().getSimpleName())
                .errorMessage(e.getMessage())
                .stackTrace(ExceptionUtil.stacktraceToString(e))
                .requestUri(request.getRequestURI())
                .requestMethod(request.getMethod())
                .requestParams(request.getParameterMap())
                .timestamp(LocalDateTime.now())
                .retryCount(0) // 初始重试次数
                .build();

        // TODO: 如果需要获取 POST Body 中的 userInput，需要配合 RequestWrapper 使用，此处暂略

        // 2. 调用 AI 修复服务
        return exceptionRetryService.handleException(context);
    }
}
