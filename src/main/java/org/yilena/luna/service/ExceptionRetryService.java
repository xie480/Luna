package org.yilena.luna.service;

import org.yilena.luna.exception.LunaExceptionContext;

import java.util.Map;

public interface ExceptionRetryService {
    /**
     * 处理异常上下文，尝试 AI 修复或生成人设化提示
     *
     * @param context 异常上下文
     * @return 返回给前端的响应结果
     */
    Map<String, Object> handleException(LunaExceptionContext context);
}
