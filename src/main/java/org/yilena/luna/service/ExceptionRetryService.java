package org.yilena.luna.service;

import org.yilena.luna.exception.LunaExceptionContext;

import java.util.Map;

/**
 * 异常重试服务接口，负责在主流程异常后生成修复建议或兜底响应。
 * 该接口用于承接异常上下文分析结果，决定是否向前端返回可重试的补救信息。
 */
public interface ExceptionRetryService {
    /**
     * 处理异常上下文，尝试执行 AI 修复或生成兜底提示。
     *
     * @param context 异常上下文
     * @return 返回给前端的响应结果
     */
    Map<String, Object> handleException(LunaExceptionContext context);
}
