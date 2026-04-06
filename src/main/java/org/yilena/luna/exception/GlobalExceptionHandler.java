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

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String STATUS_PENDING_APPROVAL = "pending_approval";

    private final ExceptionRetryService exceptionRetryService;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleException(Exception e, HttpServletRequest request) {
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
