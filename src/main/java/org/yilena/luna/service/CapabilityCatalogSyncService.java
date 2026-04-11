package org.yilena.luna.service;

/**
 * 能力目录同步服务接口，负责把各类 MCP 服务端暴露的工具、提示词、资源和工作流能力
 * 同步到本地统一能力目录中，供后续检索和编排使用。
 */
public interface CapabilityCatalogSyncService {

    void syncFromServers();
}
