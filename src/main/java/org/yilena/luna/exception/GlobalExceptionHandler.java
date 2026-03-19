package org.yilena.luna.exception;

import cn.hutool.core.exceptions.ExceptionUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.yilena.luna.exception.impl.AuthException;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.service.ExceptionRetryService;

import java.nio.charset.StandardCharsets;
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
        if(e instanceof AuthException){
            log.warn("用户认证失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }else if(e instanceof NeedApprovalException){
            throw new RuntimeException(e);
        }
        log.error("捕获全局异常: {}", e.getMessage(), e);

        // 尝试获取用户输入的 Body 内容
        String userInput = "无法获取";
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] buf = wrapper.getContentAsByteArray();
            if (buf.length > 0) {
                try {
                    String encoding = request.getCharacterEncoding();
                    if (encoding == null) {
                        encoding = StandardCharsets.UTF_8.name();
                    }
                    userInput = new String(buf, encoding);
                } catch (Exception ex) {
                    log.warn("解析请求体失败: {}", ex.getMessage());
                    userInput = "解析失败";
                }
            } else {
                userInput = "Body为空或未被读取";
            }
        }

        // 1. 构建异常上下文
        LunaExceptionContext context = LunaExceptionContext.builder()
                .errorType(e.getClass().getSimpleName())
                .errorMessage(e.getMessage())
                .stackTrace(ExceptionUtil.stacktraceToString(e))
                .requestUri(request.getRequestURI())
                .requestMethod(request.getMethod())
                .requestParams(request.getParameterMap())
                .userInput(userInput)
                .timestamp(LocalDateTime.now())
                .retryCount(0) // 初始重试次数
                .build();

        // 2. 调用 AI 修复服务
        return exceptionRetryService.handleException(context);
    }
}
