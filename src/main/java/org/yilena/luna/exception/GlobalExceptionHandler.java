package org.yilena.luna.exception;

import cn.hutool.core.exceptions.ExceptionUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.MessageConstants;
import org.yilena.luna.constants.ResultStatusConstants;
import org.yilena.luna.exception.impl.AuthException;
import org.yilena.luna.exception.impl.NeedApprovalException;
import org.yilena.luna.service.ExceptionRetryService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 全局异常处理器，负责统一拦截认证异常、审批中断和系统异常，并返回标准响应结构。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /**
     * 审批中断场景的统一状态值。
     */
    private static final String STATUS_PENDING_APPROVAL = "pending_approval";

    /**
     * 异常重试服务，用于根据异常上下文生成可恢复的错误响应。
     */
    private final ExceptionRetryService exceptionRetryService;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleException(Exception e, HttpServletRequest request) {
        /**
         * 认证异常和审批中断优先按业务语义返回，避免落入通用 500 处理逻辑。
         */
        if (e instanceof AuthException) {
            log.warn("用户认证失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    JsonFieldConstants.STATUS, ResultStatusConstants.UNAUTHORIZED,
                    JsonFieldConstants.MESSAGE, e.getMessage() != null ? e.getMessage() : MessageConstants.UNAUTHORIZED_DEFAULT
            ));
        } else if (e instanceof NeedApprovalException approvalException) {
            log.info("触发审批中断: {}", e.getMessage());
            String taskId = approvalException.getApprovalTask() == null ? "" : approvalException.getApprovalTask().getTaskId();
            return ResponseEntity.ok(Map.of(
                    JsonFieldConstants.STATUS, STATUS_PENDING_APPROVAL,
                    JsonFieldConstants.MESSAGE, MessageConstants.APPROVAL_REQUIRED,
                    JsonFieldConstants.TASK_ID, taskId
            ));
        }

        log.error("捕获全局异常: {}", e.getMessage(), e);

        /**
         * 通用异常场景下尽可能提取原始请求体，构造完整异常上下文供重试服务判断。
         */
        String userInput = MessageConstants.UNKNOWN_INPUT;
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
                    userInput = MessageConstants.REQUEST_BODY_PARSE_FAILED;
                }
            } else {
                userInput = MessageConstants.REQUEST_BODY_EMPTY;
            }
        }

        /**
         * 组装异常上下文后交给异常重试服务处理，统一输出标准错误响应。
         */
        LunaExceptionContext context = LunaExceptionContext.builder()
                .errorType(e.getClass().getSimpleName())
                .errorMessage(e.getMessage())
                .stackTrace(ExceptionUtil.stacktraceToString(e))
                .requestUri(request.getRequestURI())
                .requestMethod(request.getMethod())
                .requestParams(request.getParameterMap())
                .userInput(userInput)
                .timestamp(LocalDateTime.now())
                .retryCount(0)
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(exceptionRetryService.handleException(context));
    }
}
