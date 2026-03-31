package org.yilena.luna.service;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

/**
 * Agent 編排核心接口
 * 專注於 MCP Tool Calling 的決策、參數生成與執行閉環
 */
public interface AgentService {

    /**
     * 處理工具調用流程（推薦）
     * @param sessionId 業務會話ID（需穩定，可用日期/會話鍵）
     * @param input 用戶自然語言輸入
     * @return 工具執行的結果上下文，如果不需要調用工具則返回 null
     */
    String processToolCalling(String sessionId, String input);

    default String processToolCalling(String sessionId,
                                      String input,
                                      TaskRuntimeState taskState,
                                      RelationalRuntimeState relationalState) {
        return processToolCalling(sessionId, input);
    }

    /**
     * 兼容舊調用（不建議）
     * 若未傳 sessionId，將使用默認值，可能影響審批關聯精度
     */
    default String processToolCalling(String input) {
        return processToolCalling("agent-default", input);
    }
}
