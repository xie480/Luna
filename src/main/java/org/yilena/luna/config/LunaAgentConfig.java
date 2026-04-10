package org.yilena.luna.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置类用于承载 Luna Agent 相关的装配入口，当前主要作为 MCP 架构下的代理配置占位点。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LunaAgentConfig {
    /**
     * 原有基于 LangChain4j 本地工具路由的装配已迁移至 MCP 体系，当前保留该配置类作为统一配置入口。
     */
}
