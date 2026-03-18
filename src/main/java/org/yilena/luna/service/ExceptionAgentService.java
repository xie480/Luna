package org.yilena.luna.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.yilena.luna.exception.LunaExceptionContext;

/**
 * 異常分析 Agent 服務接口
 */
public interface ExceptionAgentService {

    /**
     * 分析異常並給出修復建議
     * @param context 異常上下文
     * @return 分析結果 JSON (包含 canFix, tool, params 或 message)
     */
    JsonNode analyzeException(LunaExceptionContext context);
}
