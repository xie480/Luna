package org.yilena.luna.service;

/**
 * Agent 編排核心接口
 * 專注於 MCP Tool Calling 的決策、參數生成與執行閉環
 */
public interface AgentService {

    /**
     * 處理工具調用流程
     * @param input 用戶自然語言輸入
     * @return 工具執行的結果上下文，如果不需要調用工具則返回 null
     */
    String processToolCalling(String input);
}
