package org.yilena.luna.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j Agent 配置類
 * 
 * 【v2.0 重構說明】
 * 由於已切換為手動實現的 MCP (Model Context Protocol) 架構，
 * 不再依賴 LangChain4j 的 AiServices 和 @Tool 註解掃描。
 * 
 * 工具的註冊與發現現在由 luna-mcp-server 模組負責。
 * 工具的執行由 luna-tool-executor 模組負責。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LunaAgentConfig {
    // 原有的 LunaToolRouter Bean 已移除，相關邏輯遷移至 AgentService
}
